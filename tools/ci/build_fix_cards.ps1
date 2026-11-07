# build_fix_cards.ps1 - generates one markdown fix-card per Sonar rule under out/sonar/cards/.
# Each card: rule identity, count, issue list (file:line + message), Sonar description,
# affected bundles, suggested spec mapping (0014-0017), TDD checklist.
param(
  [string]$IssuesFile = "out/sonar/issues.json",
  [string]$RulesFile = "out/sonar/java_rules_full.json",
  [string]$OutDir = "out/sonar/cards",
  [string]$ProjectKey = "colleague-edt"
)
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Split-Path -Parent (Split-Path -Parent $here)
Set-Location $repo
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$issues = Get-Content $IssuesFile -Raw | ConvertFrom-Json
$rules = Get-Content $RulesFile -Raw | ConvertFrom-Json
$ruleMap = @{}
foreach ($r in $rules) { $ruleMap[$r.key] = $r }

# Spec mapping by rule (from specs 0014-0017)
$specMap = @{
  'java:S2095'='0014';'java:S2093'='0014';'java:S2142'='0014';'java:S3516'='0014';'java:S3400'='0014';
  'java:S2153'='0014';'java:S2589'='0014';'java:S1764'='0014';'java:S2583'='0014';'java:S1871'='0014';
  'java:S2886'='0014';'java:S3077'='0014';'java:S1153'='0014';'java:S1155'='0014';
  'java:S106'='0015';'java:S112'='0015';'java:S2696'='0015';'java:S3398'='0015';'java:S1450'='0015';
  'java:S4276'='0015';'java:S107'='0015';'java:S1186'='0015';
  'java:S1192'='0016';'java:S3776'='0016';'java:S135'='0016';'java:S6126'='0016';'java:S5665'='0016';
  'java:S3358'='0016';'java:S125'='0016';'java:S1141'='0016';'java:S1068'='0016';'java:S1172'='0016';
  'java:S1119'='0016';'java:S1066'='0016';'java:S3012'='0016';
  'java:S1611'='0017';'java:S1612'='0017';'java:S1602'='0017';'java:S6201'='0017';'java:S6204'='0017';
  'java:S6208'='0017';'java:S6353'='0017';'java:S100'='0017';
  'Web:TableWithoutCaptionCheck'='0017';'css:S4666'='0017';
}

$groups = $issues | Group-Object rule | Sort-Object Count -Descending

foreach ($g in $groups) {
  $rule = $g.Name
  $spec = if ($specMap.ContainsKey($rule)) { $specMap[$rule] } else { '0014' }
  $r = $ruleMap[$rule]
  $name = if ($r) { $r.name } else { $rule }
  $sev = if ($r) { $r.severity } else { '' }
  $type = if ($r) { $r.type } else { '' }
  $desc = if ($r) { $r.mdDesc } else { '' }
  if (-not $desc -and $r) { $desc = $r.htmlDesc }

  $safeRule = $rule -replace '[^A-Za-z0-9._-]','_'
  $card = Join-Path $OutDir "$safeRule.md"

  $sb = New-Object System.Text.StringBuilder
  [void]$sb.AppendLine("# Fix card: $rule")
  [void]$sb.AppendLine("")
  [void]$sb.AppendLine("- Rule: $rule")
  [void]$sb.AppendLine("- Name: $name")
  [void]$sb.AppendLine("- Severity/type: $sev / $type")
  [void]$sb.AppendLine("- Count: $($g.Count)")
  [void]$sb.AppendLine("- Spec: specs/todo/$spec-*.md")
  [void]$sb.AppendLine("- Sonar key (for gate): $($rule -replace '^java:','')")
  [void]$sb.AppendLine("")
  [void]$sb.AppendLine("## Sonar description")
  [void]$sb.AppendLine("")
  [void]$sb.AppendLine($desc)
  [void]$sb.AppendLine("")
  [void]$sb.AppendLine("## Issues")
  [void]$sb.AppendLine("")
  foreach ($i in ($g.Group | Sort-Object component,line)) {
    $comp = $i.component -replace ("^" + [regex]::Escape($ProjectKey) + ":"), ''
    [void]$sb.AppendLine("- ``${comp}:$($i.line)`` - $($i.message)")
  }
  [void]$sb.AppendLine("")
  $bundles = $g.Group | ForEach-Object { ($_.component -split '/')[1] } | Sort-Object -Unique
  [void]$sb.AppendLine("## Affected bundles")
  [void]$sb.AppendLine("")
  foreach ($b in $bundles) { [void]$sb.AppendLine("- $b") }
  [void]$sb.AppendLine("")
  [void]$sb.AppendLine("## Fix protocol (per specs/0008 correction protocol)")
  [void]$sb.AppendLine("")
  [void]$sb.AppendLine("1. Branch: ``fix/sonar-$($rule -replace '^java:','')`` (split by bundle if >20 issues).")
  [void]$sb.AppendLine("2. Curator: read this card + the spec; record red-state (issue count before).")
  [void]$sb.AppendLine("3. Test-author: add/adjust tests proving the defect (red).")
  [void]$sb.AppendLine("4. Implementer: minimal change; one commit per issue (``fix: <rule> <file>:<line>``).")
  [void]$sb.AppendLine("5. Verifier: run ``mvnw clean verify`` + ``sonar_gate.ps1 -Rule $($rule -replace '^java:','')`` -> 0 new.")
  [void]$sb.AppendLine("6. Integrator: merge into main with ``chore: merge fix/sonar-...``; update baseline.")
  [void]$sb.AppendLine("")
  [void]$sb.AppendLine("## TDD checklist")
  [void]$sb.AppendLine("")
  [void]$sb.AppendLine("- [ ] Red: failing test reproduces the issue")
  [void]$sb.AppendLine("- [ ] Green: minimal fix")
  [void]$sb.AppendLine("- [ ] Refactor: keep diff minimal")
  [void]$sb.AppendLine("- [ ] ``mvnw.cmd clean verify`` green")
  [void]$sb.AppendLine("- [ ] ``sonar_gate.ps1 -Rule $($rule -replace '^java:','')`` green (0 new)")
  [void]$sb.AppendLine("- [ ] verdict.json written to out/sonar/verdicts/")

  Set-Content -Path $card -Value $sb.ToString() -Encoding UTF8
}

Write-Output "generated $($groups.Count) cards in $OutDir"
