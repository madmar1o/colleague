# bridge_smoke.ps1 — самопроверка отладочного моста после деплоя сборки.
# Против живого EDT с мостом (-Dcolleague.webbridge.port=N): 9 проверок
# транспорта и состояния, отчёт out\bridge_smoke_<run>.md, exit 0/1.
# Только чтение: никаких send; setMode доходит до plan и обратно.
param(
  [int]$Port = 8791,
  [string]$OutDir = 'out'
)
$ErrorActionPreference = "Stop"
$UTF8 = New-Object System.Text.UTF8Encoding($false)
$results = New-Object System.Collections.Generic.List[string]
$failed = 0

function Add-Result([string]$name, [bool]$ok, [string]$detail) {
  $script:results.Add("[$(if ($ok) { ' OK ' } else { 'FAIL' })] $name :: $detail")
  if (-not $ok) { $script:failed++ }
}

# 1) слушатель порта принадлежит нашему EDT (javaw), а не чужому процессу
$listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
$okPort = $false
$detail = "порт $Port не слушается"
if ($listener) {
  $proc = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener.OwningProcess)"
  $detail = "владелец: " + $proc.Name + " (pid " + $proc.ProcessId + ")"
  $okPort = ($proc.Name -eq 'javaw.exe')
}
Add-Result "port-owner" $okPort $detail
if (-not $okPort) {
  # продолжать бессмысленно: чужой процесс отвечает случайными ответами
  Add-Result "abort" $false "см. предыдущую строку; дальше проверки не идут"
  $stamp = Get-Date -Format yyyyMMddHHmm
  [IO.File]::WriteAllText("$OutDir\bridge_smoke_$stamp.md", ($results -join "`r`n"), $UTF8)
  Write-Output ($results -join "`r`n")
  exit 1
}

# 2) GET / — страница с token
try {
  $page = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/" -UseBasicParsing -TimeoutSec 10
  $m = [regex]::Match($page.Content, "token:\s*'([0-9a-f]{32})'")
  $token = $null
  if ($m.Success) { $token = $m.Groups[1].Value }
  $hasPage = ($page.StatusCode -eq 200) -and ($page.Content -match 'id="composer"')
  Add-Result "page-token" ($hasPage -and $token) "HTTP $($page.StatusCode), token=$([bool]$token), page=$hasPage"
} catch {
  Add-Result "page-token" $false ("GET / упал: " + $_.Exception.Message)
  $token = $null
}

# 3) POST /call без токена -> 401
try {
  $f = Join-Path $env:TEMP 'bs-noauth.json'
  [IO.File]::WriteAllText($f, '{"m":"setMode","a":{"mode":"default"}}', $UTF8)
  $code = 0
  try {
    [void](Invoke-WebRequest -Uri "http://127.0.0.1:$Port/call" -Method Post -ContentType 'application/json' -InFile $f -UseBasicParsing -TimeoutSec 10)
    $code = 200
  } catch {
    $code = [int]$_.Exception.Response.StatusCode
  }
  Add-Result "call-noauth-401" ($code -eq 401) "статус $code (ожидался 401 token required)"
} catch { Add-Result "call-noauth-401" $false $_.Exception.Message }

# 4) GET /events без токена -> 401
try {
  $code = 0
  try {
    [void](Invoke-WebRequest -Uri "http://127.0.0.1:$Port/events" -UseBasicParsing -TimeoutSec 10)
    $code = 200
  } catch { $code = [int]$_.Exception.Response.StatusCode }
  Add-Result "events-noauth-401" ($code -eq 401) "статус $code (ожидался 401 token required)"
} catch { Add-Result "events-noauth-401" $false $_.Exception.Message }

# 5) GET /events с токеном: HTTP 200 и тело — JSON-массив
try {
  $h = @{ 'x-colleague-token' = $token }
  $e = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/events" -Headers $h -UseBasicParsing -TimeoutSec 10
  $body = [Text.Encoding]::UTF8.GetString($e.RawContentStream.ToArray())
  $isArr = $body.StartsWith('[') -and $body.EndsWith(']')
  Add-Result "events-token-200" (($e.StatusCode -eq 200) -and $isArr) "HTTP $($e.StatusCode), тело $([Math]::Min(60,$body.Length)) симв."]
} catch { Add-Result "events-token-200" $false $_.Exception.Message }

# 6-7) setMode в оба режима (по R4 спеки): оба 200, батч — JSON-массив
$modeOk = $true
$modeDetail = ''
foreach ($mode in 'plan','default') {
  $f = Join-Path $env:TEMP ('bs-mode-' + $mode + '.json')
  [IO.File]::WriteAllText($f, ('{"m":"setMode","a":{"mode":"' + $mode + '"}}'), $UTF8)
  try {
    $c = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/call" -Method Post -Headers @{ 'x-colleague-token' = $token } -ContentType 'application/json' -InFile $f -UseBasicParsing -TimeoutSec 10
    $batch = [Text.Encoding]::UTF8.GetString($c.RawContentStream.ToArray())
    $okMode = ($c.StatusCode -eq 200) -and $batch.StartsWith('[')
    if (-not $okMode) { $modeOk = $false; $modeDetail += ("setMode {0}: HTTP {1}; " -f $mode, $c.StatusCode) }
  } catch {
    $modeOk = $false
    $code = try { [int]$_.Exception.Response.StatusCode } catch { -1 }
    $modeDetail += ("setMode {0}: статус {1}; " -f $mode, $code)
  }
}
Add-Result "setmode-both-200" $modeOk $(if ($modeOk) { "plan+default вернули JSON-батч" } else { $modeDetail })

# 8) GET /events после page-call: 200 и массив (события режима должны прийти)
try {
  $h = @{ 'x-colleague-token' = $token }
  $e = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/events" -Headers $h -UseBasicParsing -TimeoutSec 10
  $body = [Text.Encoding]::UTF8.GetString($e.RawContentStream.ToArray())
  Add-Result "events-after-calls" (($e.StatusCode -eq 200) -and ($body -match '^\[')) "HTTP $($e.StatusCode), первые байты: $([Text.Encoding]::UTF8.GetString($e.RawContentStream.ToArray()).Substring(0,[Math]::Min(40,$body.Length)))"
} catch { Add-Result "events-after-calls" $false $_.Exception.Message }

# 9) страница — это chat.html с мостом-бутстрэпом (вторичная структурная проверка)
try {
  $page2 = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/" -UseBasicParsing -TimeoutSec 10
  $ok2 = ($page2.Content -match 'colleagueWeb') -and ($page2.Content -match '/call')
  Add-Result "page-bootstrap" $ok2 "colleagueWeb + /call в HTML: $ok2"
} catch { Add-Result "page-bootstrap" $false $_.Exception.Message }

# отчёт
$stamp = Get-Date -Format yyyyMMddHHmm
[IO.File]::WriteAllText("$OutDir\bridge_smoke_$stamp.md", ($results -join "`r`n"), $UTF8)
Write-Output ($results -join "`r`n")
$total = $results.Count
Write-Output ("SUMMARY: $(($total - $failed))/$total passed, fails=$failed, run=$stamp")
if ($failed -gt 0) { exit 1 } else { exit 0 }
