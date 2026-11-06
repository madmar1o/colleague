# jacoco_report.ps1 - aggregates every target/jacoco.exec produced by the test
# bundles into one coverage XML that SonarQube can ingest.
#
# Tycho runs each test bundle in its own JVM, so JaCoCo writes one .exec per
# bundle. The CLI aggregator merges them and diffs against the production class
# directories (and sources) of the whole reactor.
#
# Output: out/ci/jacoco.xml (paths are relative to the repository root).
param(
  [string]$OutFile,
  [string]$CliJar,
  [string]$Version = '0.8.12'
)
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Split-Path -Parent (Split-Path -Parent $here)
if (-not $OutFile) { $OutFile = Join-Path $repo "out/ci/jacoco.xml" }
$outDir = Split-Path -Parent $OutFile
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
if (-not $CliJar) { $CliJar = Join-Path $outDir "tools/jacococli.jar" }
if (-not (Test-Path $CliJar)) {
  New-Item -ItemType Directory -Force -Path (Split-Path -Parent $CliJar) | Out-Null
  $url = "https://repo1.maven.org/maven2/org/jacoco/org.jacoco.cli/$Version/org.jacoco.cli-$Version-nodeps.jar"
  Write-Output "jacoco: downloading $url"
  Invoke-WebRequest -Uri $url -OutFile $CliJar -UseBasicParsing
}

$execFiles = @(Get-ChildItem -Path (Join-Path $repo 'bundles') -Recurse -Filter 'jacoco.exec' -ErrorAction SilentlyContinue |
  ForEach-Object { $_.FullName })
if ($execFiles.Count -eq 0) {
  Write-Output "jacoco: no jacoco.exec found - tests probably did not run with the agent"
  exit 2
}

$classDirs = @(Get-ChildItem -Path (Join-Path $repo 'bundles') -Recurse -Directory -Filter 'classes' -ErrorAction SilentlyContinue |
  Where-Object { $_.FullName -match '\\target\\classes$' } |
  ForEach-Object { $_.FullName })
$sourceDirs = @()
foreach ($b in (Get-ChildItem -Path (Join-Path $repo 'bundles') -Directory)) {
  $src = Join-Path $b.FullName 'src'
  if (Test-Path $src) { $sourceDirs += $src }
}

$cliArgs = @('-jar', $CliJar, 'report')
foreach ($e in $execFiles) { $cliArgs += $e }
foreach ($c in $classDirs) { $cliArgs += @('--classfiles', $c) }
foreach ($s in $sourceDirs) { $cliArgs += @('--sourcefiles', $s) }
$cliArgs += @('--xml', $OutFile)

Write-Output ("jacoco: {0} exec file(s), {1} class dir(s) -> {2}" -f $execFiles.Count, $classDirs.Count, $OutFile)
& java @cliArgs
if ($LASTEXITCODE -ne 0) { throw "jacoco cli exited with $LASTEXITCODE" }
if (-not (Test-Path $OutFile)) { throw "jacoco report was not written: $OutFile" }
Write-Output "jacoco: OK"
exit 0
