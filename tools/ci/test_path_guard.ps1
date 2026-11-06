# test_path_guard.ps1 - cross-check of the chat.html path guard.
#
# Node extracts pathIsWorkspaceSafe from chat.html and runs the very same
# case set (path-guard-cases.json) that the Java WorkspacePathGuardTest
# reads, so the JS and the Java implementations cannot drift apart.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/ci/test_path_guard.ps1 [-Html <chat.html>] [-Cases <json>]
# Exit code 0 when every case matches, 1 on a mismatch, 2 on bad usage.
param(
  [string]$Html,
  [string]$Cases
)
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Split-Path -Parent (Split-Path -Parent $here)
if (-not $Html) {
  $Html = Join-Path $repo "bundles/com.mad.edt.colleague.ui/web/chat.html"
}
if (-not $Cases) {
  $Cases = Join-Path $repo "bundles/com.mad.edt.colleague.tests/src/com/mad/edt/colleague/chat/path-guard-cases.json"
}
$runner = Join-Path $here "test_path_guard.mjs"
node $runner $Html $Cases
exit $LASTEXITCODE
