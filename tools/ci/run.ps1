# run.ps1 - the local mirror of the CI pipeline (hygiene, build, sonar).
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/ci/run.ps1 -Stage all
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/ci/run.ps1 -Stage build
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/ci/run.ps1 -Stage sonar -Rule S3516
#
# Exit codes: 0 green, 1 red, 2 cannot evaluate.
param(
  [ValidateSet('hygiene', 'build', 'sonar', 'all')]
  [string]$Stage = 'all',
  [string]$Rule,
  [string]$BaseRef = 'origin/main',
  [string]$OutDir
)
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Split-Path -Parent (Split-Path -Parent $here)
if (-not $OutDir) { $OutDir = Join-Path $repo "out/ci" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$log = Join-Path $OutDir "run-$Stage-$stamp.log"

function Step([string]$title, [scriptblock]$body) {
  Write-Output ("== {0} ==" -f $title)
  & $body
  if ($LASTEXITCODE -ne 0) {
    Write-Output ("FAIL: {0} (exit {1})" -f $title, $LASTEXITCODE)
    exit 1
  }
  Write-Output ("OK:   {0}" -f $title)
}

function Invoke-Hygiene {
  # 1. every commit of the range has a Conventional-Commit subject
  $patterns = '^(chore|feat|fix|docs|refactor|test|build|ci|revert)(\([a-z0-9._-]+\))?: .{3,}'
  $range = "HEAD"
  if ($BaseRef -and (git rev-parse --verify --quiet $BaseRef)) { $range = "$BaseRef..HEAD" }
  $bad = @()
  foreach ($subject in (git log --format='%s' $range)) {
    if ($subject -notmatch $patterns) { $bad += $subject }
  }
  if ($bad.Count -gt 0) {
    Write-Output "hygiene: bad commit subject(s):"
    $bad | ForEach-Object { Write-Output "  $_" }
    exit 1
  }
  Write-Output "hygiene: commit subjects OK ($(if ($range -ne 'HEAD') { $range } else { 'HEAD' }))"

  # 2. no tracked secrets / oversized files
  $forbidden = @('.env', '.key', '.pem', '.token', 'credentials', 'secret')
  $hits = @()
  foreach ($f in (git ls-files)) {
    foreach ($p in $forbidden) {
      if ($f -match ((('(?i)' + [regex]::Escape($p)) + '(?![a-z])'))) { $hits += $f }
    }
  }
  if ($hits.Count -gt 0) { Write-Output "hygiene: forbidden tracked file(s): $($hits -join ', ')"; exit 1 }
  $big = @()
  foreach ($f in (git ls-files)) {
    $p = Join-Path $repo $f
    if ((Test-Path $p) -and ((Get-Item $p).Length -gt 1MB)) { $big += $f }
  }
  if ($big.Count -gt 0) { Write-Output "hygiene: files over 1MB: $($big -join ', ')"; exit 1 }

  # 3. repository is free of third-party AI product names
  & (Join-Path $here 'sanitize_check.ps1')
}

function Invoke-Build {
  $zulu = 'C:\Program Files\Zulu\zulu-17'
  if (Test-Path $zulu) { $env:JAVA_HOME = $zulu }
  Push-Location $repo
  try {
    & .\mvnw.cmd -B clean verify
    if ($LASTEXITCODE -ne 0) { exit 1 }
    & (Join-Path $here 'test_path_guard.ps1')
    if ($LASTEXITCODE -ne 0) { exit 1 }
  } finally {
    Pop-Location
  }
}

function Invoke-Sonar([string]$rule) {
  & (Join-Path $here 'sonar_scan.ps1')
  if ($LASTEXITCODE -ne 0) { exit 1 }
  if ($rule) {
    & (Join-Path $here 'sonar_gate.ps1') -Rule $rule
  } else {
    & (Join-Path $here 'sonar_gate.ps1')
  }
  if ($LASTEXITCODE -ne 0) { exit 1 }
}

try {
  switch ($Stage) {
    'hygiene' { Step 'hygiene' { Invoke-Hygiene } }
    'build' { Step 'build' { Invoke-Build } }
    'sonar' { Step 'sonar' { Invoke-Sonar $Rule } }
    'all' {
      Step 'hygiene' { Invoke-Hygiene }
      Step 'build' { Invoke-Build }
      Step 'sonar' { Invoke-Sonar $Rule }
    }
  }
  Write-Output "run.ps1: $Stage PASS"
  exit 0
} catch {
  Write-Output "run.ps1: ERROR - $($_.Exception.Message)"
  exit 2
}
