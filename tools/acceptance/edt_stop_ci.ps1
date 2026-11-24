<#
.SYNOPSIS
  Stops the CI EDT instance gracefully.
.DESCRIPTION
  Closes the main window (WM_CLOSE via CloseMainWindow) of the CI EDT process
  recorded by edt_launch_ci.ps1, waits for a clean exit, and only then falls
  back to Stop-Process. The javaw child follows the parent window close; a
  leftover javaw is killed by name only when it belongs to the closed tree.
  Safe to call when nothing is running (no-op).
.PARAMETER Profile
  CI workspace holding ci_logs\ci_edt_pid.txt (default: EDT_CI_PROFILE).
#>
[CmdletBinding()]
param(
  [string]$Profile = $(if ($env:EDT_CI_PROFILE) { $env:EDT_CI_PROFILE } else { throw 'no -Profile and EDT_CI_PROFILE is not set' })
)
$ErrorActionPreference = 'SilentlyContinue'
$pidFile = Join-Path $Profile 'ci_logs\ci_edt_pid.txt'
if (-not (Test-Path $pidFile)) { Write-Host 'no CI EDT pid file, nothing to stop'; exit 0 }
$procId = [int](Get-Content $pidFile -Raw).Trim()
$p = Get-Process -Id $procId -ErrorAction SilentlyContinue
if (-not $p) { Write-Host 'CI EDT already gone'; Remove-Item $pidFile -Force; exit 0 }
[void]$p.CloseMainWindow()
if (-not $p.WaitForExit(60000)) {
  Write-Host 'graceful close timed out, stopping the process tree'
  Stop-Process -Id $procId -Force
}
Get-CimInstance Win32_Process -Filter "Name='javaw.exe'" |
  Where-Object { $_.CommandLine -and $_.CommandLine -like "*$Profile*" } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Remove-Item $pidFile -Force
Write-Host 'CI EDT stopped'
