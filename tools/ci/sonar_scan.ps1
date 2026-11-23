# sonar_scan.ps1 - runs the SonarQube scanner for this checkout and collects
# the resulting issues to a JSON file.
#
# Two modes:
#   * full scan  (default)   : docker scanner -> wait for the CE task -> collect
#   * -CollectOnly           : only fetch issues of an already analysed project
#
# The CI flow analyses each branch into a dedicated project key
# (colleague-edt-ci by default) because SonarQube Community has no branch
# analysis; the key stays on the server as the CI analysis history.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/ci/sonar_scan.ps1 [-ProjectKey colleague-edt-ci]
#        [-SonarHost <url>] [-CollectOnly] [-TimeoutSec 900]
# Without -SonarHost the SONAR_HOST_URL environment variable wins, then the
# local loopback instance.
param(
  [string]$ProjectKey = "colleague-edt-ci",
  [string]$SonarHost = $(if ($env:SONAR_HOST_URL) { $env:SONAR_HOST_URL } else { 'http://127.0.0.1:9000' }),
  # the scanner runs inside a container and cannot reach the host's loopback;
  # by default its URL is resolved from the SonarQube container's bridge IP
  [string]$ScannerContainer = "sonarqube",
  [string]$Token = $env:SONAR_TOKEN,
  [string]$TokenFile = "$env:USERPROFILE\.sonar\scan-token.txt",
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

function Wait-CeTask([string]$key, [int]$timeout, [string]$previousId) {
  # right after upload the "current" task is still the PREVIOUS analysis, so
  # first wait for a new task id to appear, then wait for that task itself
  $deadline = (Get-Date).AddSeconds($timeout)
  $newTask = $null
  while ((Get-Date) -lt $deadline) {
    try {
      $task = (Invoke-RestMethod -Uri "$SonarHost/api/ce/component?component=$key" -Headers $hdr -TimeoutSec 30).current
    } catch {
      $task = $null
    }
    if ($task -and $task.id -and $task.id -ne $previousId) { $newTask = $task; break }
    Start-Sleep -Seconds 5
  }
  if (-not $newTask) { throw "SonarQube analysis of $key did not start in $timeout s" }
  while ((Get-Date) -lt $deadline) {
    $ce = (Invoke-RestMethod -Uri "$SonarHost/api/ce/task?id=$($newTask.id)" -Headers $hdr -TimeoutSec 30).task
    if ($ce.status -eq "SUCCESS") { return }
    if ($ce.status -eq "FAILED" -or $ce.status -eq "CANCELED") {
      throw "SonarQube analysis $($ce.status) for ${key}: $($ce.errorMessage)"
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
  $scannerUrl = $SonarHost
  if ($ScannerContainer) {
    $sip = (& docker inspect -f "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}" $ScannerContainer).Trim()
    if (-not $sip) { throw "container $ScannerContainer not found for the scanner URL" }
    $port = ([Uri]$SonarHost).Port
    $scannerUrl = "http://${sip}:$port"
  }
  $scanArgs = @(
    "run", "--rm",
    "-e", "SONAR_HOST_URL=$scannerUrl",
    "-e", "SONAR_TOKEN=$Token",
    "-v", "${repo}:/usr/src",
    "sonarsource/sonar-scanner-cli:5",
    "-Dsonar.projectKey=$ProjectKey",
    "-Dsonar.projectName=Colleague CI",
    "-Dsonar.projectVersion=ci",
    "-Dsonar.scm.disabled=true"
  )
  foreach ($p in $ExtraProps) { $scanArgs += $p }
  $prevTaskId = ""
  try {
    $prevTaskId = (Invoke-RestMethod -Uri "$SonarHost/api/ce/component?component=$ProjectKey" -Headers $hdr -TimeoutSec 30).current.id
  } catch { }
  & docker @scanArgs
  if ($LASTEXITCODE -ne 0) { throw "sonar scanner exited with $LASTEXITCODE" }
  Wait-CeTask $ProjectKey $TimeoutSec $prevTaskId
}

$issues = Get-Issues $ProjectKey
$file = Join-Path $OutDir "$ProjectKey-issues.json"
ConvertTo-Json -InputObject @($issues) -Depth 6 | Set-Content -Path $file -Encoding UTF8
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
