# sonar_scan.ps1 - runs the SonarQube scanner for this checkout and collects
# the resulting issues to a JSON file.
#
# Two modes:
#   * full scan  (default)   : docker scanner -> wait for the CE task -> collect
#   * -CollectOnly           : only fetch issues of an already analysed project
#
# The CI flow analyses each branch into a dedicated project key
# (colleague-edt-ci by default) because SonarQube Community has no branch
# analysis; the key is deleted by the workflow afterwards.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/ci/sonar_scan.ps1 [-ProjectKey colleague-edt-ci]
#        [-SonarHost http://127.0.0.1:9000] [-CollectOnly] [-TimeoutSec 900]
param(
  [string]$ProjectKey = "colleague-edt-ci",
  [string]$SonarHost = "http://127.0.0.1:9000",
  [string]$Token = $env:SONAR_TOKEN,
  [string]$TokenFile = "$env:USERPROFILE\.sonar\dsh-scan-token.txt",
  [string]$OutDir,
  [int]$TimeoutSec = 900,
  [string[]]$ExtraProps = @(),
  [switch]$CollectOnly
)
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Split-Path -Parent (Split-Path -Parent $here)
if (-not $OutDir) { $OutDir = Join-Path $repo "out/sonar" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

if (-not $Token) {
  if (Test-Path $TokenFile) {
    $Token = (Get-Content $TokenFile -Raw).Trim()
  } else {
    throw "no SonarQube token: pass -Token, set SONAR_TOKEN or create $TokenFile"
  }
}
$cred = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${Token}:"))
$hdr = @{ Authorization = "Basic $cred" }

function Wait-CeTask([string]$key, [int]$timeout) {
  $deadline = (Get-Date).AddSeconds($timeout)
  while ((Get-Date) -lt $deadline) {
    try {
      $task = (Invoke-RestMethod -Uri "$SonarHost/api/ce/component?component=$key" -Headers $hdr -TimeoutSec 30).current
    } catch {
      $task = $null
    }
    if ($task -and $task.status) {
      if ($task.status -eq "SUCCESS") { return }
      if ($task.status -eq "FAILED" -or $task.status -eq "CANCELED") {
        throw "SonarQube analysis $($task.status) for ${key}: $($task.errorMessage)"
      }
    }
    Start-Sleep -Seconds 5
  }
  throw "SonarQube analysis of $key did not finish in $timeout s"
}

function Get-Issues([string]$key) {
  $all = @()
  $page = 1
  while ($true) {
    $uri = "$SonarHost/api/issues/search?componentKeys=$key&resolved=false&ps=500&p=$page"
    $resp = Invoke-RestMethod -Uri $uri -Headers $hdr -TimeoutSec 60
    $all += $resp.issues
    if ($all.Count -ge $resp.total -or $resp.issues.Count -eq 0) { break }
    $page++
  }
  return $all
}

if (-not $CollectOnly) {
  if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "docker is required for the scanner step"
  }
  $scanArgs = @(
    "run", "--rm",
    "-e", "SONAR_HOST_URL=$SonarHost",
    "-e", "SONAR_TOKEN=$Token",
    "-v", "${repo}:/usr/src",
    "sonarsource/sonar-scanner-cli:5",
    "-Dsonar.projectKey=$ProjectKey",
    "-Dsonar.projectName=Colleague CI",
    "-Dsonar.projectVersion=ci",
    "-Dsonar.scm.disabled=true"
  )
  foreach ($p in $ExtraProps) { $scanArgs += $p }
  & docker @scanArgs
  if ($LASTEXITCODE -ne 0) { throw "sonar scanner exited with $LASTEXITCODE" }
  Wait-CeTask $ProjectKey $TimeoutSec
}

$issues = Get-Issues $ProjectKey
$file = Join-Path $OutDir "$ProjectKey-issues.json"
$issues | ConvertTo-Json -Depth 6 | Set-Content -Path $file -Encoding UTF8
$byRule = $issues | Group-Object rule | Sort-Object Count -Descending
$summary = [pscustomobject]@{
  project = $ProjectKey
  total   = $issues.Count
  file    = $file
  byRule  = @{}
}
foreach ($g in $byRule) { $summary.byRule[$g.Name] = $g.Count }
Write-Output ("sonar: {0} issues in {1} -> {2}" -f $issues.Count, $ProjectKey, $file)
$byRule | Select-Object -First 15 | ForEach-Object { Write-Output ("  {0} x{1}" -f $_.Name, $_.Count) }
return $summary
