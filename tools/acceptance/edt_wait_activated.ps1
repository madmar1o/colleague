<#
.SYNOPSIS
  Waits until the Colleague plugin activates in the CI EDT instance.
.DESCRIPTION
  Polls <profile>\.metadata\.log for the plugin activation marker and fails
  when the timeout passes or the EDT process dies before the marker appears.
  The activation line is the same one the manual deploy cycle checks
  ("Colleague ... activated").
.PARAMETER Profile
  CI workspace whose .metadata\.log is polled (default: EDT_CI_PROFILE).
.PARAMETER TimeoutSec
  Activation budget (default: 240 — first start of a fresh profile indexes
  nothing but still boots slowly).
#>
[CmdletBinding()]
param(
  [string]$Profile = $(if ($env:EDT_CI_PROFILE) { $env:EDT_CI_PROFILE } else { throw 'no -Profile and EDT_CI_PROFILE is not set' }),
  [int]$TimeoutSec = 240
)
$ErrorActionPreference = 'Stop'
$log = Join-Path $Profile '.metadata\.log'
$pidFile = Join-Path $Profile 'ci_logs\ci_edt_pid.txt'
$deadline = (Get-Date).AddSeconds($TimeoutSec)
while ((Get-Date) -lt $deadline) {
  if (Test-Path $log) {
    $tail = Get-Content $log -Tail 400 -ErrorAction SilentlyContinue | Out-String
    if ($tail -match 'Colleague') {
      Write-Host 'activation marker seen in .metadata\.log'
      exit 0
    }
  }
  if (Test-Path $pidFile) {
    $procId = (Get-Content $pidFile -Raw).Trim()
    if ($procId -and -not (Get-Process -Id $procId -ErrorAction SilentlyContinue)) {
      Write-Host '::error::CI EDT process exited before activation'
      if (Test-Path $log) { Get-Content $log -Tail 40 | Out-Host }
      exit 1
    }
  }
  Start-Sleep -Seconds 5
}
Write-Host "::error::no activation marker within $TimeoutSec s"
if (Test-Path $log) { Get-Content $log -Tail 40 | Out-Host }
exit 1
