# javadoc_check.ps1 - Javadoc gate for new public API in production sources.
#
# Project rule (AGENTS.md): every new function needs Javadoc. This check walks
# the diff BaseRef..HEAD, collects the lines ADDED under bundles/*/src (test
# bundles excluded), and flags every public/protected type or method
# declaration on those lines that has no Javadoc block directly above it.
# Annotation lines may sit between the doc and the declaration; @Override
# members inherit the documented contract and are exempt; private members are
# not public API and are exempt.
#
# Exit codes: 0 pass, 1 violation found, 2 cannot evaluate (fail-closed).
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/ci/javadoc_check.ps1 \
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

function AddedLines([string[]]$diffLines) {
  # parse unified diff hunks into a map: file path -> set of added line numbers
  # (numbering on the b/ side of the diff)
  $map = @{}
  $file = ''
  $bLine = 0
  foreach ($line in $diffLines) {
    if ($line -like 'diff --git *') { $file = ($line -split ' b/')[1]; continue }
    if ($line -match '^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@') {
      $bLine = [int]$Matches[1]
      continue
    }
    if ($line -match '^(\+\+\+|---)') { continue }
    if ($line -like '+*') {
      if (-not $map.ContainsKey($file)) { $map[$file] = @{} }
      $map[$file][$bLine] = $true
      $bLine++
    } elseif ($line -like '-*') {
      # removed lines do not advance the b-side counter
    } else {
      $bLine++
    }
  }
  return ,$map
}

# a public/protected type or method/constructor declaration (abstract counts;
# fields, constructors-as-new-name lines and local variables do not match)
$DECL = '^\s*(?:public|protected)\s+(?:(?:static|final|abstract|default|synchronized|strictfp|native)\s+)*[\w$<>\[\],.? ]+?[\w$>\]](?:\s*\([^;{]*\))?\s*[;{]'

if ((Invoke-GitQuiet @('rev-parse', '--git-dir')) -ne 0) { Write-Output 'javadoc: ERROR - not a git repository'; exit 2 }
if ((Invoke-GitQuiet @('rev-parse', '--verify', $BaseRef)) -ne 0) { Write-Output "javadoc: ERROR - base ref '$BaseRef' not found"; exit 2 }

$diff = Invoke-GitDiff @('diff', '--unified=3', "${BaseRef}..HEAD")
$added = AddedLines $diff

$violations = @()
foreach ($file in $added.Keys) {
  if ($file -notmatch '^bundles/[^/]+/src/.*\.java$') { continue }        # production sources only
  if ($file -match '(^|/)com\.mad\.edt\.colleague\.[\w.]*tests(/|$)') { continue }
  $path = Join-Path $RepoRoot ($file -replace '/', '\')
  if (-not (Test-Path $path)) { continue }                                 # deleted in this range
  $lines = [IO.File]::ReadAllLines($path)
  $isInterface = @($lines | Where-Object { $_ -match '^\s*public\s+interface\s' }).Count -gt 0
  foreach ($ln in ($added[$file].Keys | Sort-Object)) {
    if ($ln -gt $lines.Count) { continue }
    $text = $lines[$ln - 1]
    # interface members are implicitly public API even without a modifier
    $implicitPublic = ($isInterface -and $text -match '^\s*[\w$<>\[\],.? ]+?[\w$>\]]\s*\([^;{]*\)\s*;')
    if ($text -notmatch $DECL -and -not $implicitPublic) { continue }
    if ($text -match '^\s*public\s+[A-Z][\w$]*\s*\(') { continue }        # constructors: the type doc covers them
    # walk up through annotations and blank lines to find the doc block;
    # remember the first annotation seen - @Override inherits a documented contract
    $k = $ln - 2
    $firstAnnotation = $null
    while ($k -ge 0) {
      $prev = $lines[$k]
      if ($prev -match '^\s*(@\w+)') {
        if (-not $firstAnnotation) { $firstAnnotation = $Matches[1] }
        $k--; continue
      }
      if ($prev -match '^\s*$') { $k--; continue }
      break
    }
    if ($firstAnnotation -eq '@Override') { continue }                     # inherited contract
    $documented = ($k -ge 0 -and $lines[$k] -match '\*/\s*$')
    if (-not $documented) { $violations += ("{0}:{1}: {2}" -f $file, $ln, $text.Trim()) }
  }
}

if ($violations.Count -gt 0) {
  Write-Output "javadoc: FAIL - $($violations.Count) new public declaration(s) without Javadoc:"
  foreach ($v in ($violations | Sort-Object)) { Write-Output "  $v" }
  exit 1
}
Write-Output 'javadoc: PASS - every new public declaration in the diff is documented'
exit 0
