# version_freeze_check.ps1 - guards the frozen build identity of the plugin.
#
# Project rule (AGENTS.md): nobody may change Bundle-Version, the target
# platform definitions under targets/, or the Tycho version property in
# bom/pom.xml as an incidental side effect of a code change. This check walks
# the diff BaseRef..HEAD and fails on any touch of those frozen coordinates.
# A commit subject carrying '[allow-version-change]' (e.g.
# 'build: move to the 2026.2 target platform [allow-version-change]')
# releases the freeze for the whole range.
#
# Exit codes: 0 pass, 1 violation found, 2 cannot evaluate (fail-closed).
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/ci/version_freeze_check.ps1 \
#        [-RepoRoot .] [-BaseRef origin/main]
param(
  [string]$RepoRoot = '.',
  [string]$BaseRef = 'origin/main'
)
$ErrorActionPreference = 'Stop'

function Invoke-GitDiff([string[]]$gitArgs) {
  # run git inside the target repository and return its stdout lines verbatim
  Push-Location $RepoRoot
  try { & git @gitArgs 2>&1 | ForEach-Object { "$_" } } finally { Pop-Location -ErrorAction SilentlyContinue }
}

function Invoke-GitQuiet([string[]]$gitArgs) {
  # run git silently, reporting only the exit code
  Push-Location $RepoRoot
  try { & git @gitArgs 2>&1 | Out-Null; $LASTEXITCODE } finally { Pop-Location -ErrorAction SilentlyContinue }
}

if ((Invoke-GitQuiet @('rev-parse', '--git-dir')) -ne 0) { Write-Output 'freeze: ERROR - not a git repository'; exit 2 }
if ((Invoke-GitQuiet @('rev-parse', '--verify', $BaseRef)) -ne 0) { Write-Output "freeze: ERROR - base ref '$BaseRef' not found"; exit 2 }

$subjects = Invoke-GitDiff @('log', '--format=%s', "${BaseRef}..HEAD")
if ($subjects -join ' ' -match '\[allow-version-change\]') {
  Write-Output 'freeze: PASS - explicit [allow-version-change] marker in the range'
  exit 0
}

$diff = Invoke-GitDiff @('diff', '--unified=0', "${BaseRef}..HEAD")
$problems = @()
$changedFile = ''
foreach ($line in $diff) {
  if ($line -like 'diff --git *') {
    # take the b-side path; forward slashes, relative to the repo root
    $changedFile = ($line -split ' b/')[1]
    continue
  }
  if ($changedFile -notmatch '^(.*/)?META-INF/MANIFEST\.MF$' -and
      $changedFile -notmatch '^targets/' -and
      $changedFile -ne 'bom/pom.xml') { continue }
  if ($line -notmatch '^[+-]' -or $line -match '^(\+\+\+|---)') { continue }
  $isFrozenTouch =
    ($changedFile -match 'META-INF/MANIFEST\.MF$' -and $line -match '^[+-]\s*Bundle-Version\s*:') -or
    ($changedFile -match '^targets/.*\.target$') -or
    ($changedFile -eq 'bom/pom.xml' -and $line -match '<tycho\.version>')
  if ($isFrozenTouch) {
    $problems += ("{0}: {1}" -f $changedFile, $line.Substring(1).Trim())
  }
}

# any change inside a .target file or its pom is frozen wholesale; catch files
# that were added/removed entirely without content lines in the diff above
$nameStatus = Invoke-GitDiff @('diff', '--name-only', "${BaseRef}..HEAD")
foreach ($f in $nameStatus) {
  if ($f -match '^targets/.*\.target$' -and -not ($problems | Where-Object { $_ -like "$f*" })) {
    $problems += "${f}: target platform file touched without [allow-version-change]"
  }
}
$problems = @($problems | Select-Object -Unique)

if ($problems.Count -gt 0) {
  Write-Output 'freeze: FAIL - frozen build coordinates changed:'
  foreach ($p in $problems) { Write-Output "  $p" }
  Write-Output '  (if intentional, put [allow-version-change] in the commit subject)'
  exit 1
}
Write-Output 'freeze: PASS - Bundle-Version, targets/ and tycho.version untouched'
exit 0
