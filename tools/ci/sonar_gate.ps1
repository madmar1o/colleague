# sonar_gate.ps1 - quality gate for a scan result against the committed
# baseline (ci/sonar-baseline.json) and the thresholds (ci/quality-gate.json).
#
# Checks:
#   1. no new BLOCKER issues      (maxNewBlockers)
#   2. no new CRITICAL issues     (maxNewCriticals)
#   3. no rule may gain issues    (failOnRuleIncrease)
#   4. -Rule <Sxxxx>: that rule must have zero issues (fix branch gate)
#   5. project coverage >= minCoverage (JaCoCo report fed into the analysis)
#
# Exit code 0 = PASS, 1 = FAIL, 2 = cannot evaluate (fail-closed).
param(
  [string]$ProjectKey = "colleague-edt-ci",
  # SONAR_HOST_URL points at the instance (the public address in CI); the
  # loopback default is the local development setup
  [string]$SonarHost = $(if ($env:SONAR_HOST_URL) { $env:SONAR_HOST_URL } else { 'http://127.0.0.1:9000' }),
  [string]$Token = $env:SONAR_TOKEN,
  [string]$TokenFile = "$env:USERPROFILE\.sonar\scan-token.txt",
  [string]$BaselineFile,
  [string]$QualityFile,
  [string]$IssuesFile,
  [string]$Rule,
  [switch]$AllowMissingCoverage
)
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Split-Path -Parent (Split-Path -Parent $here)
if (-not $BaselineFile) { $BaselineFile = Join-Path $repo "ci/sonar-baseline.json" }
if (-not $QualityFile) { $QualityFile = Join-Path $repo "ci/quality-gate.json" }
if (-not $IssuesFile) { $IssuesFile = Join-Path $repo "out/sonar/$ProjectKey-issues.json" }
if (-not $Token -and (Test-Path $TokenFile)) { $Token = (Get-Content $TokenFile -Raw).Trim() }
$cred = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${Token}:"))
$hdr = @{ Authorization = "Basic $cred" }

function Fail([string]$message) {
  Write-Output "GATE: FAIL - $message"
  exit 1
}
function Bad([string]$message) {
  Write-Output "GATE: ERROR - $message"
  exit 2
}

if (-not (Test-Path $BaselineFile)) { Bad "baseline file not found: $BaselineFile" }
if (-not (Test-Path $QualityFile)) { Bad "quality file not found: $QualityFile" }
if (-not (Test-Path $IssuesFile)) { Bad "issue snapshot not found: $IssuesFile" }

$baseline = Get-Content $BaselineFile -Raw | ConvertFrom-Json
$quality = Get-Content $QualityFile -Raw | ConvertFrom-Json
$issues = Get-Content $IssuesFile -Raw | ConvertFrom-Json

$currentCounts = @{}
foreach ($g in ($issues | Group-Object rule)) { $currentCounts[$g.Name] = $g.Count }
$baselineCounts = @{}
foreach ($p in $baseline.counts.PSObject.Properties) { $baselineCounts[$p.Name] = [int]$p.Value }

$identity = {
  param($issue)
  $file = ($issue.component -split ':')[-1]
  ($issue.rule + '|' + $file + '|' + $issue.line + '|' + $issue.message)
}
$baselineIds = @{}
foreach ($id in $baseline.identities) { $baselineIds[$id] = $true }

$newIssues = @()
foreach ($issue in $issues) {
  $id = & $identity $issue
  if (-not $baselineIds.ContainsKey($id)) { $newIssues += $issue }
}

$newBlockers = @($newIssues | Where-Object { $_.severity -eq 'BLOCKER' })
$newCriticals = @($newIssues | Where-Object { $_.severity -eq 'CRITICAL' })

$problems = @()
if ($newBlockers.Count -gt [int]$quality.maxNewBlockers) {
  $problems += "new BLOCKER issues: $($newBlockers.Count) > $($quality.maxNewBlockers)"
}
if ($newCriticals.Count -gt [int]$quality.maxNewCriticals) {
  $problems += "new CRITICAL issues: $($newCriticals.Count) > $($quality.maxNewCriticals)"
}

if ($quality.failOnRuleIncrease) {
  foreach ($ruleKey in $currentCounts.Keys) {
    $was = 0
    if ($baselineCounts.ContainsKey($ruleKey)) { $was = $baselineCounts[$ruleKey] }
    if ($currentCounts[$ruleKey] -gt $was) {
      $problems += "rule $ruleKey grew: $was -> $($currentCounts[$ruleKey])"
    }
  }
}

if ($Rule) {
  $target = $Rule
  if ($target -notmatch '^java:' -and $target -match '^S\d+$') { $target = "java:$target" }
  $count = 0
  if ($currentCounts.ContainsKey($target)) { $count = $currentCounts[$target] }
  if ($count -ne 0) { $problems += "target rule ${target} still has $count issue(s)" }
}

if ($Token) {
  try {
    $m = Invoke-RestMethod -Uri "$SonarHost/api/measures/component?component=$ProjectKey&metricKeys=coverage" -Headers $hdr -TimeoutSec 30
    $cov = ($m.component.measures | Where-Object metric -eq 'coverage').value
  } catch {
    $cov = $null
  }
  if ($null -eq $cov) {
    if (-not ($AllowMissingCoverage -or $quality.allowMissingCoverage)) {
      $problems += "coverage metric is missing (JaCoCo report not fed into the analysis)"
    }
  } elseif ([double]$cov -lt [double]$quality.minCoverage) {
    $problems += "coverage $cov < $($quality.minCoverage)"
  }
}

Write-Output ("baseline {0}: {1} issues; current: {2} issues; new: {3}" -f `
  $baseline.head, $baseline.total, $issues.Count, $newIssues.Count)
foreach ($p in $problems) { Write-Output "  violation: $p" }

if ($problems.Count -gt 0) { Fail ($problems -join '; ') }
Write-Output "GATE: PASS"
exit 0
