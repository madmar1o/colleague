# sanitize_check.ps1 - fails when tracked files mention third-party AI products
# (the repository is an independent implementation and must stay clean) or
# machine-specific absolute paths (the repository is published and portable;
# the runner machine is described by environment variables, never by paths).
#
# Only product/brand names of other AI tools are banned. The plugin's own
# "agent" feature (skills, tool bridge, agent UX docs) is legitimate and
# whitelisted by design.
#
# Allow-list: ci/sanitize-allow.txt, one regular expression per line (applies
# to file paths, not to content).
#
# Exit 0 = clean, 1 = findings, 2 = cannot evaluate.
param(
  [string]$AllowFile,
  [string]$RepoRoot
)
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = if ($RepoRoot) { $RepoRoot } else { Split-Path -Parent (Split-Path -Parent $here) }
if (-not $AllowFile) { $AllowFile = Join-Path $repo "ci/sanitize-allow.txt" }

$patterns = @(
  '(?i)\bdsh\b',
  '(?i)deep-?seek',
  '(?i)\bopencode\b',
  '(?i)\bclaude\b',
  '(?i)\bcopilot\b',
  '(?i)\bchatgpt\b',
  '(?i)\banthropic\b',
  # machine-specific absolute paths: the build machine is configured through
  # environment variables (JAVA_HOME_17), the repository must not know them
  'C:\\Program Files\\Zulu',
  'C:\\actions-runner',
  'C:\\Users\\',
  'C:\\WS\\',
  'C:\\open-project'
)
$allow = @()
if (Test-Path $AllowFile) {
  $allow = Get-Content $AllowFile | Where-Object { $_.Trim() -and -not $_.TrimStart().StartsWith('#') }
}

Push-Location $repo
try {
  $files = git ls-files
} finally {
  Pop-Location
}
if (-not $files) { Write-Output "sanitize: no tracked files"; exit 2 }

$skipDirs = '^(\.git/|target/|out/)'
$findings = @()
foreach ($rel in $files) {
  if ($rel -match $skipDirs) { continue }
  if ($rel -match '(?i)\.(png|jpg|jpeg|gif|ico|jar|zip|class|bin|woff2?)$') { continue }
  $allowed = $false
  foreach ($a in $allow) { if ($rel -match $a) { $allowed = $true; break } }
  if ($allowed) { continue }
  $path = Join-Path $repo $rel
  if (-not (Test-Path $path)) { continue }
  $lineNo = 0
  foreach ($line in Get-Content $path) {
    $lineNo++
    foreach ($p in $patterns) {
      if ($line -match $p) {
        $findings += [pscustomobject]@{ file = $rel; line = $lineNo; pattern = $p; text = $line.Trim() }
        break
      }
    }
  }
}

if ($findings.Count -gt 0) {
  Write-Output "sanitize: FAIL - $($findings.Count) finding(s)"
  $findings | Select-Object -First 50 | ForEach-Object {
    Write-Output ("  {0}:{1}: {2}" -f $_.file, $_.line, $_.text)
  }
  exit 1
}
Write-Output "sanitize: PASS - $($files.Count) tracked file(s) checked"
exit 0
