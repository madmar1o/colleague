# test_ci_gates.ps1 - canon runner for the convention gates (javadoc, version
# freeze, machine-path sanitize).
#
# Builds a throwaway git repository from the before/after file pairs stored in
# ci/javadoc-cases.json and ci/version-freeze-cases.json, then runs the real
# check scripts against it with -BaseRef HEAD~1. Same exit contract as the
# checks themselves: 0 all cases behave as expected, 1 a case diverged,
# 2 the runner could not evaluate.
#
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File tools/ci/test_ci_gates.ps1
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Split-Path -Parent (Split-Path -Parent $here)

function Write-FileContent([string]$root, [string]$rel, [string]$content) {
  $path = Join-Path $root ($rel -replace '/', '\')
  $dir = Split-Path -Parent $path
  if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
  if ($null -eq $content) { $content = '' }
  [IO.File]::WriteAllText($path, $content.Replace("`r", ''), (New-Object Text.UTF8Encoding($false)))
}

function Run-CaseSet([string]$setLabel, [string]$casesFile, [string]$checkScript, [switch]$NoBaseRef) {
  if (-not (Test-Path $casesFile)) { Write-Host "MISSING canon file: $casesFile"; return 2 }
  $canon = Get-Content $casesFile -Raw | ConvertFrom-Json
  $failed = 0
  foreach ($case in $canon.cases) {
    $work = Join-Path $env:TEMP ("gate-cases-" + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $work | Out-Null
    $eaOld = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    Push-Location $work
    try {
      git init -q . | Out-Null
      git config user.email gate@test.local | Out-Null
      git config user.name gate | Out-Null
      foreach ($f in $case.files) { Write-FileContent $work $f.path $f.before }
      git add -A | Out-Null
      git commit -q --allow-empty -m 'chore: base state' | Out-Null
      foreach ($f in $case.files) { Write-FileContent $work $f.path $f.after }
      git add -A | Out-Null
      $subject = if ($case.subject) { $case.subject } else { 'test: case fixture' }
      git commit -q -m $subject | Out-Null
      if ($NoBaseRef) {
        & $checkScript -RepoRoot $work | Out-Host
      } else {
        & $checkScript -RepoRoot $work -BaseRef 'HEAD~1' | Out-Host
      }
      $exit = $LASTEXITCODE
      $got = if ($exit -eq 0) { 'pass' } elseif ($exit -eq 1) { 'fail' } else { 'error' }
      if ($got -ne $case.expected) {
        Write-Host ("  DIVERGED {0}/{1}: expected {2}, got {3}" -f $setLabel, $case.name, $case.expected, $got)
        $failed = $failed + 1
      } else {
        Write-Host ("  ok {0}/{1} ({2})" -f $setLabel, $case.name, $got)
      }
    } finally {
      Pop-Location
      $ErrorActionPreference = $eaOld
      Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
    }
  }
  return $failed
}

$total = 0
Write-Host '== javadoc gate cases =='
$total += [int](Run-CaseSet 'javadoc' (Join-Path $repo 'ci/javadoc-cases.json') (Join-Path $repo 'tools/ci/javadoc_check.ps1'))
Write-Host '== version freeze gate cases =='
$total += [int](Run-CaseSet 'freeze' (Join-Path $repo 'ci/version-freeze-cases.json') (Join-Path $repo 'tools/ci/version_freeze_check.ps1'))
Write-Host '== machine-path sanitize cases =='
$total += [int](Run-CaseSet 'sanitize' (Join-Path $repo 'ci/sanitize-cases.json') (Join-Path $repo 'tools/ci/sanitize_check.ps1') -NoBaseRef)
if ($total -gt 0) { Write-Host "ci gate tests: $total diverged"; exit 1 }
Write-Host 'ci gate tests: all cases OK'
exit 0
