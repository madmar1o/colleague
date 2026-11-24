<#
.SYNOPSIS
  Deploys the freshly built Colleague bundles into one or more EDT
  installations the way the developer's manual cycle does: version-stamped
  jars into the shared p2 pool plus a bundles.info patch of each target
  installation.
.DESCRIPTION
  An EDT install is provisioned as a platform; the Colleague feature is not a
  p2-managed part of that profile, so the proven transport is: copy the three
  built bundles under <symbolic>_<version>.<stamp>.jar names into the pool,
  then rewrite the colleague lines of each installation's bundles.info to the
  new stamp. The same stamp goes into each jar's MANIFEST (the jar is rebuilt
  entry by entry — in-place manifest patching corrupts it).
  All target installations share one p2 pool, so they are stamped together:
  deploys that stamp installations independently make the pool cleanup of one
  run delete the jars the other installation still references.
  Machine-neutral: everything comes from parameters and runner environment
  variables (EDT_CI_HOME, EDT_CI_DEV_HOME, EDT_CI_POOL), never from hardcoded
  paths.
.PARAMETER EdtHomes
  Target EDT installation roots (default: EDT_CI_HOME and EDT_CI_DEV_HOME from
  the environment, each that is set).
.PARAMETER Pool
  Shared p2 pool plugins directory (default: EDT_CI_POOL, then
  %USERPROFILE%\.p2\pool\plugins).
.PARAMETER RepoDir
  Repository root holding the built bundles (default: the repository root of
  this script).
.PARAMETER ScratchCopy
  Copy the FIRST EdtHome to this directory first and deploy into the copy
  instead — the install-check job proves the deploy on a throwaway instance
  without touching any live installation.
.PARAMETER KeepStale
  Number of newest qualifier stamps to keep in the pool (default 5); jars
  outside the window that no scanned installation references are removed.
  Removal of a jar another running instance has locked is skipped, not fatal.
.EXAMPLE
  powershell -File tools/acceptance/edt_deploy_feature.ps1
  powershell -File tools/acceptance/edt_deploy_feature.ps1 -ScratchCopy "$env:USERPROFILE/CI_EDT/install-check"
#>
[CmdletBinding()]
param(
  [string[]]$EdtHomes = $(if (@($env:EDT_CI_HOME, $env:EDT_CI_DEV_HOME | Where-Object { $_ }).Count -gt 0) { @($env:EDT_CI_HOME, $env:EDT_CI_DEV_HOME | Where-Object { $_ }) } else { $null }),
  [string]$Pool = '',
  [string]$RepoDir = '',
  [string]$ScratchCopy = '',
  [int]$KeepStale = 5
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

if (-not $EdtHomes -or $EdtHomes.Count -eq 0) {
  throw 'no -EdtHomes and neither EDT_CI_HOME nor EDT_CI_DEV_HOME is set'
}
foreach ($h in $EdtHomes) {
  if (-not (Test-Path (Join-Path $h 'configuration\org.eclipse.equinox.simpleconfigurator\bundles.info'))) {
    throw "no bundles.info under $h — not an EDT installation root?"
  }
}
if (-not $RepoDir) { $RepoDir = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path }
if (-not $Pool) {
  if ($env:EDT_CI_POOL) { $Pool = $env:EDT_CI_POOL }
  else { $Pool = Join-Path $env:USERPROFILE '.p2\pool\plugins' }
}
if (-not (Test-Path $Pool)) { throw "no p2 pool at $Pool (set EDT_CI_POOL in the runner .env)" }

if ($ScratchCopy) {
  if (-not (Test-Path (Join-Path $ScratchCopy '1cedt.exe'))) {
    if (Test-Path $ScratchCopy) { Remove-Item -Recurse -Force $ScratchCopy }
    Copy-Item -Recurse $EdtHomes[0] $ScratchCopy
  }
  $EdtHomes = @((Resolve-Path $ScratchCopy).Path)
  Write-Host "deploying into a scratch copy: $($EdtHomes[0])"
}

# single-tenant guard: deploying while an instance of a target installation
# runs replaces locked state and yields half-initialized bundles
foreach ($h in $EdtHomes) {
  $mine = Get-CimInstance Win32_Process -Filter "Name='1cedt.exe'" |
    Where-Object { $_.CommandLine -and $_.CommandLine -like "*$h*" }
  if ($mine) {
    throw ("$h is still running: PID " + (($mine | ForEach-Object { $_.ProcessId }) -join ', '))
  }
}

$stamp = 'v' + (Get-Date -Format 'yyyyMMddHHmm')
$modules = @(
  'com.mad.edt.colleague',
  'com.mad.edt.colleague.context',
  'com.mad.edt.colleague.ui'
)

$tmp = Join-Path $env:TEMP ("colleague-acceptance-" + $stamp + "-" + $PID)
New-Item -ItemType Directory -Path $tmp -Force | Out-Null
try {
  foreach ($m in $modules) {
    $src = Join-Path $RepoDir ("bundles\" + $m + "\target\" + $m + "-1.0.0-SNAPSHOT.jar")
    if (-not (Test-Path $src)) { throw "missing built jar: $src (run the build first)" }

    # the jar is rebuilt entry by entry: in-place patching of the manifest
    # entry truncates its head and the bundle then installs with a broken manifest
    $patched = Join-Path $tmp ($m + '.jar')
    $in = [System.IO.Compression.ZipFile]::OpenRead($src)
    $outStream = [System.IO.File]::Open($patched, [System.IO.FileMode]::Create)
    $out = New-Object System.IO.Compression.ZipArchive($outStream, [System.IO.Compression.ZipArchiveMode]::Create)
    $manifestText = $null
    $manifestEntry = $in.GetEntry('META-INF/MANIFEST.MF')
    if ($manifestEntry -ne $null) {
      $reader = New-Object System.IO.StreamReader($manifestEntry.Open())
      $manifestText = $reader.ReadToEnd()
      $reader.Close()
    }
    if ($manifestText -eq $null) { $out.Dispose(); $outStream.Dispose(); $in.Dispose(); throw "no manifest in $src" }
    $mv = [regex]::Match($manifestText, 'Bundle-Version: (\d+\.\d+\.\d+)(?:\.qualifier|\.v\d+)?')
    if (-not $mv.Success) { $out.Dispose(); $outStream.Dispose(); $in.Dispose(); throw "manifest of $m has no Bundle-Version line to stamp" }
    $base = $mv.Groups[1].Value
    $patchedManifest = [regex]::Replace($manifestText, 'Bundle-Version: \d+\.\d+\.\d+[\w.]*', "Bundle-Version: $base.$stamp")
    if ($patchedManifest -notmatch [regex]::Escape("Bundle-Version: $base.$stamp")) {
      $out.Dispose(); $outStream.Dispose(); $in.Dispose()
      throw "manifest of $m has no Bundle-Version line to patch"
    }
    foreach ($e in $in.Entries) {
      $newEntry = $out.CreateEntry($e.FullName)
      $dstStream = $newEntry.Open()
      if ($e.FullName -eq 'META-INF/MANIFEST.MF') {
        # the patched text goes in as the entry is created: a created-mode
        # entry cannot be reopened after its stream was closed
        $writer = New-Object System.IO.StreamWriter($dstStream)
        $writer.Write($patchedManifest)
        $writer.Flush(); $writer.Close()
      } else {
        $srcStream = $e.Open()
        $srcStream.CopyTo($dstStream)
        $srcStream.Close(); $dstStream.Close()
      }
    }
    $in.Dispose(); $out.Dispose(); $outStream.Dispose()

    $dst = Join-Path $Pool ($m + '_' + $base + '.' + $stamp + '.jar')
    Copy-Item $patched $dst -Force
    $check = [System.IO.Compression.ZipFile]::OpenRead($dst)
    $ce = $check.GetEntry('META-INF/MANIFEST.MF')
    $cr = New-Object System.IO.StreamReader($ce.Open())
    $cman = $cr.ReadToEnd(); $cr.Close(); $check.Dispose()
    if (-not $cman.StartsWith('Manifest-Version:') -or $cman -notmatch 'Require-Bundle') {
      throw ('pool manifest of ' + $m + ' is damaged: first bytes "' + $cman.Substring(0, [Math]::Min(40, $cman.Length)) + '"')
    }
    Write-Host "pool: $dst"
  }
} finally {
  Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}

foreach ($edtHome in $EdtHomes) {
  $info = Join-Path $edtHome 'configuration\org.eclipse.equinox.simpleconfigurator\bundles.info'
  # the dropped placeholder bundle leaves its line in an installed bundles.info;
  # simpleconfigurator would then look for a jar that no longer exists, so the
  # stale line goes away before the version patch and the count guard
  $text = [System.IO.File]::ReadAllText($info)
  $text = [regex]::Replace($text, '(?m)^com\.mad\.edt\.colleague\.ui\.common,.*\r?\n', '')
  # entry-count guard: 3 modules x 2 occurrences each (id + jar name per line);
  # a replacement that erases lines must be caught before the file is written
  $before = ([regex]::Matches($text, 'com\.mad\.edt\.colleague')).Count
  if ($before -ne 6) { throw "bundles.info of $edtHome carries $before colleague matches, expected 6" }
  # the registered qualifier is whatever the previous deploy left, not a constant
  $pattern = '(com\.mad\.edt\.colleague[\w.]*,)\d+\.\d+\.\d+[\w.]*(,\.+[\w./-]*?com\.mad\.edt\.colleague[\w.]*_)\d+\.\d+\.\d+[\w.]*\.jar(,\d+,false)'
  $text2 = [regex]::Replace($text, $pattern, ('${1}' + $base + '.' + $stamp + '${2}' + $base + '.' + $stamp + '.jar${3}'))
  if ($text2 -eq $text) { throw "bundles.info of $edtHome has no colleague entries to patch" }
  if (([regex]::Matches($text2, 'com\.mad\.edt\.colleague')).Count -ne $before) { throw 'patching lost colleague entries' }
  [System.IO.File]::WriteAllText($info, $text2, (New-Object System.Text.UTF8Encoding($false)))
  Write-Host "bundles.info of $edtHome patched to $stamp"
}

# Stale cleanup is reference-checked across ALL EDT installations sharing
# this pool: the pool is shared, so a jar another install points to must
# survive this deploy (deleting it leaves a dangling bundles.info line and a
# broken launch). Enumerate installations from the p2 profile registry, from
# the unregistered CI_EDT tree (the CI instance and install-check scratch
# copies are not registered), and from every target of this deploy.
$referencedNames = @{}
$poolRoot = Split-Path (Split-Path $Pool -Parent) -Parent
$infos = @(Get-ChildItem $poolRoot -Filter 'bundles.info' -Recurse -ErrorAction SilentlyContinue |
  Where-Object { $_.FullName -match 'org\.eclipse\.equinox\.simpleconfigurator' } | ForEach-Object { $_.FullName })
$registry = Join-Path $poolRoot 'profiles.info'
if (Test-Path $registry) {
  foreach ($line in (Get-Content $registry)) {
    $parts = $line -split '\|\|'
    if ($parts.Count -ge 4) {
      $candidate = Join-Path ($parts[3] -replace '\\\\', '\') 'configuration\org.eclipse.equinox.simpleconfigurator\bundles.info'
      if ((Test-Path $candidate) -and ($infos -notcontains $candidate)) { $infos += $candidate }
    }
  }
}
$ciRoot = Join-Path $env:USERPROFILE 'CI_EDT'
if (Test-Path $ciRoot) {
  $infos += @(Get-ChildItem $ciRoot -Filter 'bundles.info' -Recurse -ErrorAction SilentlyContinue |
    ForEach-Object { $_.FullName })
}
$infos += @($EdtHomes | ForEach-Object { Join-Path $_ 'configuration\org.eclipse.equinox.simpleconfigurator\bundles.info' })
foreach ($i in ($infos | Where-Object { $_ } | Sort-Object -Unique)) {
  foreach ($line in (Select-String -Path $i -Pattern 'com\.mad\.edt\.colleague' -ErrorAction SilentlyContinue)) {
    if ($line.Line -match '([^/,]+\.jar)') { $referencedNames[$Matches[1]] = $true }
  }
}
$stamps = Get-ChildItem $Pool -Filter 'com.mad.edt.colleague*_*.jar' |
  ForEach-Object { if ($_.Name -match '_(\d+\.\d+\.\d+v\d+)\.jar$') { $Matches[1] } } |
  Sort-Object -Unique -Descending | Select-Object -First $KeepStale
$stamps = @($stamps) + ($base + '.' + $stamp)
Get-ChildItem $Pool -Filter 'com.mad.edt.colleague*_*.jar' | ForEach-Object {
  $stampOf = if ($_.Name -match '_(\d+\.\d+\.\d+v\d+)\.jar$') { $Matches[1] } else { '' }
  if (-not $referencedNames[$_.Name] -and ($stamps -notcontains $stampOf)) {
    try { Remove-Item $_.FullName -Force; Write-Host "removed stale: $($_.Name)" }
    catch { Write-Host "stale jar locked, kept: $($_.Name)" }
  }
}
Write-Host "DEPLOY QUALIFIER=$stamp"
