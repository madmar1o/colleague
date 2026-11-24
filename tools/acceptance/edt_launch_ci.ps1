<#
.SYNOPSIS
  Starts the dedicated CI EDT instance with the Colleague web bridge enabled.
.DESCRIPTION
  Launches 1cedt.exe of the CI installation against the CI workspace (profile)
  with -Dcolleague.webbridge.port=<port> and -Dcolleague.open.view=true, so
  bridge_smoke.ps1 can exercise the transport without any UI interaction.
  Refuses to start when an instance of THIS installation/profile is already
  running (the CI instance is single-tenant; concurrency is serialized by the
  live-acceptance job).
.PARAMETER EdtHome
  CI EDT installation root (default: EDT_CI_HOME).
.PARAMETER Profile
  Workspace directory for -data (default: EDT_CI_PROFILE).
.PARAMETER Port
  Web bridge port (default: EDT_CI_PORT or 8791 — kept away from the manual
  developer bridge on 8790).
.PARAMETER LogDir
  Directory for stdout/stderr redirection and the launched PID file
  (default: <profile>\ci_logs).
#>
[CmdletBinding()]
param(
  [string]$EdtHome = $(if ($env:EDT_CI_HOME) { $env:EDT_CI_HOME } else { throw 'no -EdtHome and EDT_CI_HOME is not set' }),
  [string]$Profile = $(if ($env:EDT_CI_PROFILE) { $env:EDT_CI_PROFILE } else { throw 'no -Profile and EDT_CI_PROFILE is not set' }),
  [int]$Port = $(if ($env:EDT_CI_PORT) { [int]$env:EDT_CI_PORT } else { 8791 }),
  [string]$LogDir = ''
)
$ErrorActionPreference = 'Stop'
$edtExe = Join-Path $EdtHome '1cedt.exe'
if (-not (Test-Path $edtExe)) { throw "no 1cedt.exe at $edtExe" }
if (-not (Test-Path $Profile)) { New-Item -ItemType Directory -Path $Profile -Force | Out-Null }
if (-not $LogDir) { $LogDir = Join-Path $Profile 'ci_logs' }
New-Item -ItemType Directory -Path $LogDir -Force | Out-Null

# single-tenant guard: match on this profile path in the command line, not on
# any 1cedt.exe (the developer may run their own instance concurrently)
$mine = Get-CimInstance Win32_Process -Filter "Name='1cedt.exe'" |
  Where-Object { $_.CommandLine -and $_.CommandLine -like "*$Profile*" }
if ($mine) {
  throw ("CI EDT already running: PID " + (($mine | ForEach-Object { $_.ProcessId }) -join ', '))
}

$java = Join-Path $env:JAVA_HOME 'bin\javaw.exe'
if (-not (Test-Path $java)) { throw "JAVA_HOME has no bin\javaw.exe ($java)" }

$args = @('-vm', "`"$java`"", '-consoleLog', '-data', $Profile,
  '-vmargs', '-Dcolleague.open.view=true', "-Dcolleague.webbridge.port=$Port")
$p = Start-Process $edtExe -ArgumentList $args -WorkingDirectory $EdtHome -PassThru `
  -RedirectStandardOutput (Join-Path $LogDir 'stdout.txt') `
  -RedirectStandardError (Join-Path $LogDir 'stderr.txt')
"$($p.Id)" | Set-Content (Join-Path $LogDir 'ci_edt_pid.txt') -Encoding ascii
Write-Host "CI EDT started: PID=$($p.Id) port=$Port profile=$Profile"
