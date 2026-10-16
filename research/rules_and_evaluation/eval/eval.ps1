# eval.ps1 - runs testset.json through the LLM on the QIDK board and scores it.
# Usage (from the platform-tools folder):
#   powershell -ExecutionPolicy Bypass -File .\eval.ps1 -Model qwen -Limit 3   # quick check
#   powershell -ExecutionPolicy Bypass -File .\eval.ps1 -Model qwen            # full run
#   powershell -ExecutionPolicy Bypass -File .\eval.ps1 -Model llama           # compare with Llama 1B

param(
  [ValidateSet("qwen","llama")][string]$Model = "qwen",
  [string]$TestFile = "testset.json",
  [int]$Limit = 0
)

$adb = Join-Path $PSScriptRoot "adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }

if ($Model -eq "qwen") { $BundleDir = "/data/local/tmp/qwen4b" } else { $BundleDir = "/data/local/tmp/genie_bundle" }

# ---------------- Prompt (same as tests.ps1) ----------------
$System = "Extract incident report fields as JSON. Use null for anything not said. Never invent details.`n" +
          "Copy location, datetime and persons_involved using the user's exact words.`n" +
          "incident_type: Water Leak, Fire, Injury, Accident, Equipment Failure, Electrical Hazard, Gas Leak, Spill, Security, Other, or null if no incident. If a person got hurt, use Injury.`n" +
          "severity: Critical (deaths, many injured, emergency needed), High (anyone hurt, fire, gas), Medium (contained or isolated), Low (minor), or null if no incident."

$Shots = @(
  @{ u = 'There was a water leak near Conference Room 2 around 10 AM. Maintenance has already isolated the area.';
     a = '{"incident_type": "Water Leak", "severity": "Medium", "location": "Conference Room 2", "datetime": "around 10 AM", "persons_involved": null, "actions_taken": "Maintenance isolated the area", "description": "Water leak near Conference Room 2, area isolated."}' },
  @{ u = 'uh a worker cut his hand on the machine in the workshop just now';
     a = '{"incident_type": "Injury", "severity": "High", "location": "workshop", "datetime": "just now", "persons_involved": "a worker", "actions_taken": null, "description": "A worker cut his hand on a machine in the workshop."}' },
  @{ u = 'Good morning, is this working?';
     a = '{"incident_type": null, "severity": null, "location": null, "datetime": null, "persons_involved": null, "actions_taken": null, "description": null}' }
)
$Prefill = '{"incident_type":'

function Build-Prompt([string]$text) {
  $sb = New-Object System.Text.StringBuilder
  if ($Model -eq "qwen") {
    [void]$sb.Append("<|im_start|>system`n$System<|im_end|>`n")
    foreach ($s in $Shots) {
      [void]$sb.Append("<|im_start|>user`n$($s.u)<|im_end|>`n<|im_start|>assistant`n$($s.a)<|im_end|>`n")
    }
    [void]$sb.Append("<|im_start|>user`n$text<|im_end|>`n<|im_start|>assistant`n$Prefill")
  } else {
    [void]$sb.Append("<|begin_of_text|><|start_header_id|>system<|end_header_id|>`n`n$System<|eot_id|>")
    foreach ($s in $Shots) {
      [void]$sb.Append("<|start_header_id|>user<|end_header_id|>`n`n$($s.u)<|eot_id|><|start_header_id|>assistant<|end_header_id|>`n`n$($s.a)<|eot_id|>")
    }
    [void]$sb.Append("<|start_header_id|>user<|end_header_id|>`n`n$text<|eot_id|><|start_header_id|>assistant<|end_header_id|>`n`n$Prefill")
  }
  return $sb.ToString()
}

# ---------------- Run on the board ----------------
function Invoke-Model([string]$text) {
  $local = Join-Path $env:TEMP "eval_prompt.txt"
  [IO.File]::WriteAllText($local, (Build-Prompt $text), (New-Object System.Text.UTF8Encoding($false)))
  & $adb push $local "$BundleDir/eval_prompt.txt" | Out-Null
  $sw = [Diagnostics.Stopwatch]::StartNew()
  $out = & $adb shell "source /data/local/tmp/setup_qairt_env.sh > /dev/null; cd $BundleDir && genie-t2t-run -c genie_config.json --prompt_file eval_prompt.txt" 2>&1 | Out-String
  $sw.Stop()
  return @{ Out = $out; Seconds = [math]::Round($sw.Elapsed.TotalSeconds, 2) }
}

function Get-Body([string]$out) {
  $m = [regex]::Match($out, '\[BEGIN\]:(.*?)\[END\]', 'Singleline')
  if ($m.Success) { return $m.Groups[1].Value.Trim() }
  return $null
}

function Parse-Body($body) {
  if ($null -eq $body) { return $null }
  try { return (($Prefill + " " + $body) | ConvertFrom-Json) } catch { return $null }
}

# ---------------- Code rules (mirror of the planned Kotlin layer) ----------------
$InjuryWords = @('hurt','injur','bleed','blood','cut his','cut her','fracture','broke his','broke her','ankle','wrist',
                 'unconscious','fainted','collapsed','burned his','burned her','not breathing','finger',
                 'chot','ghayal','khoon','behosh')
$FatalWords  = @('fatal','dead','died','killed','not breathing','maut','mar gaya')
$EmergencyWords = @('fatal','dead','died','killed','not breathing','ambulance','emergency','many people','serious condition','trapped','maut','mar gaya')
$Negations   = @('no one','nobody','not hurt','no injur','koi ghayal nahi','koi nahi')

# Words that must appear in the transcript for the model's incident_type to be trusted
$TypeWords = [ordered]@{
  'Water Leak'        = @('leak','water','drip','pipe','flood','burst','paani')
  'Fire'              = @('fire','smoke','flame','explod','blast','bang','aag','jal ')
  'Injury'            = @('hurt','injur','bleed','blood','cut ','fracture','faint','collaps','unconscious','burned','finger','chot','ghayal','khoon')
  'Accident'          = @('accident','hit ','crash','collid','fell','fall','slip','takkar','gir ')
  'Equipment Failure' = @('not working','broken','stuck','fail','kharab','machine','printer','lift','elevator','projector')
  'Electrical Hazard' = @('wire','spark','electric','shock','switch','socket','short circuit','current')
  'Gas Leak'          = @('gas')
  'Spill'             = @('spill','oil','chemical','slippery','glass')
  'Security'          = @('stole','theft','break-in','broke into','intruder','suspicious','chori','fight')
}
$TimePattern = '\d|\bam\b|\bpm\b|ago|now|today|tonight|yesterday|morning|evening|afternoon|night|minute|hour|second|since|just|abhi|subah|kal|shaam|raat|pehle|baje'
$SevRank = @{ 'low' = 1; 'medium' = 2; 'high' = 3; 'critical' = 4 }
$HazardTypes = @('fire','gas leak','electrical hazard')

function Set-Prop($o, $name, $val) {
  if ($o.PSObject.Properties[$name]) { $o.$name = $val }
  else { $o | Add-Member -NotePropertyName $name -NotePropertyValue $val }
}

function Has-Any([string]$t, $words) { foreach ($w in $words) { if ($t.Contains($w)) { return $true } }; return $false }

function Apply-Rules($pred, [string]$text) {
  if ($null -eq $pred) { return $null }
  $t = " " + $text.ToLower() + " "
  $r = $pred | Select-Object *

  # A. Filler values ("not specified" etc.) -> null
  foreach ($f in @('location','datetime','persons_involved','actions_taken')) {
    if ($r.PSObject.Properties[$f] -and $null -eq (Norm $r.$f)) { Set-Prop $r $f $null }
  }

  # B. Date/time must look like a time expression, else null (and the app asks "When?")
  if ($null -ne (Norm $r.datetime) -and -not ([string]$r.datetime -match $TimePattern)) { Set-Prop $r "datetime" $null }

  # C. Type grounding: if the model's type has no supporting word in the transcript, re-classify by keywords
  $type = Norm $r.incident_type
  if ($null -ne $type -and $type -ne 'other') {
    $key = @($TypeWords.Keys | Where-Object { $_.ToLower() -eq $type })
    $grounded = ($key.Count -gt 0) -and (Has-Any $t $TypeWords[$key[0]])
    if (-not $grounded) {
      $hits = @($TypeWords.Keys | Where-Object { Has-Any $t $TypeWords[$_] })
      if ($hits.Count -eq 1) { Set-Prop $r "incident_type" $hits[0] } else { Set-Prop $r "incident_type" "Other" }
    }
  }

  # D. Injury / fatal keyword rules (skipped when negated, e.g. "no one was hurt")
  $negated = Has-Any $t $Negations
  if (-not $negated -and (Has-Any $t $InjuryWords)) {
    Set-Prop $r "incident_type" "Injury"
    if ((Norm $r.severity) -ne "critical") { Set-Prop $r "severity" "High" }
  }
  if (Has-Any $t $FatalWords) { Set-Prop $r "severity" "Critical" }

  # E. Critical only when the transcript justifies it; otherwise cap at High
  if ((Norm $r.severity) -eq "critical" -and -not (Has-Any $t $EmergencyWords)) { Set-Prop $r "severity" "High" }

  # F. Hazards (fire, gas, electrical) are at least Medium
  $sev = Norm $r.severity
  if (($HazardTypes -contains (Norm $r.incident_type)) -and ($null -eq $sev -or $SevRank[$sev] -lt 2)) { Set-Prop $r "severity" "Medium" }

  return $r
}

# ---------------- Scoring ----------------
function Norm($v) {
  if ($null -eq $v) { return $null }
  if ($v -is [array]) { $v = ($v -join " ") }
  $s = ([string]$v).Trim().ToLower()
  if ($s -in @("", "null", "none", "unknown", "n/a", "not specified", "not mentioned", "unspecified", "not provided", "not given")) { return $null }
  return $s
}

function Score-Enum($got, $expected) {
  $g = Norm $got
  foreach ($e in @($expected)) {
    if ($null -eq $e -and $null -eq $g) { return $true }
    if ($null -ne $e -and $null -ne $g -and $g -eq ([string]$e).ToLower()) { return $true }
  }
  return $false
}

function Score-Text($got, $keywords) {
  $g = Norm $got
  if ($null -eq $keywords) { return @{ ok = ($null -eq $g); halluc = ($null -ne $g) } }
  if ($null -eq $g) { return @{ ok = $false; halluc = $false } }
  foreach ($k in @($keywords)) { if ($g.Contains(([string]$k).ToLower())) { return @{ ok = $true; halluc = $false } } }
  return @{ ok = $false; halluc = $false }
}

function Score-Case($p, $c) {
  if ($null -eq $p) { return @{ type = $false; sev = $false; loc = $false; dt = $false; halluc = 0 } }
  $loc = Score-Text $p.location $c.location
  $dt  = Score-Text $p.datetime $c.datetime
  $h = 0; if ($loc.halluc) { $h++ }; if ($dt.halluc) { $h++ }
  return @{ type = (Score-Enum $p.incident_type $c.type); sev = (Score-Enum $p.severity $c.severity);
            loc = $loc.ok; dt = $dt.ok; halluc = $h }
}

# ---------------- Main ----------------
$cases = @((Get-Content $TestFile -Raw | ConvertFrom-Json) | ForEach-Object { $_ })
if ($Limit -gt 0) { $cases = @($cases | Select-Object -First $Limit) }

Write-Host "Model: $Model   Bundle: $BundleDir   Cases: $($cases.Count)" -ForegroundColor Cyan
$results = @()
$i = 0
foreach ($c in $cases) {
  $i++
  Write-Host ("[{0}/{1}] {2}" -f $i, $cases.Count, $c.text)
  $run   = Invoke-Model $c.text
  $body  = Get-Body $run.Out
  $pred  = Parse-Body $body
  $fixed = Apply-Rules $pred $c.text
  $sr = Score-Case $pred $c
  $sf = Score-Case $fixed $c
  $results += [pscustomobject]@{
    id = $c.id; category = $c.category; seconds = $run.Seconds; json_ok = ($null -ne $pred)
    raw_type = $sr.type; raw_sev = $sr.sev; raw_loc = $sr.loc; raw_dt = $sr.dt; halluc = $sr.halluc
    rules_type = $sf.type; rules_sev = $sf.sev; rules_loc = $sf.loc; rules_dt = $sf.dt; rules_halluc = $sf.halluc
    text = $c.text; output = $body
    fixed = $(if ($null -ne $fixed) { "type=$($fixed.incident_type) sev=$($fixed.severity) loc=$($fixed.location) time=$($fixed.datetime)" } else { "" })
  }
  Write-Host ("      json:{0}  raw/rules  type:{1}/{2} sev:{3}/{4} loc:{5}/{6} dt:{7}/{8}  ({9}s)" -f `
    $(if ($null -ne $pred) {"OK"} else {"FAIL"}), $sr.type, $sf.type, $sr.sev, $sf.sev, $sr.loc, $sf.loc, $sr.dt, $sf.dt, $run.Seconds) -ForegroundColor DarkGray
}

# ---------------- Summary ----------------
$n = $results.Count
function Pct($k) { if ($n -eq 0) { return 0 }; return [math]::Round(100.0 * $k / $n, 1) }
function CountTrue($prop) { return @($results | Where-Object { $_.$prop }).Count }

$json   = CountTrue "json_ok"
$rt = CountTrue "raw_type";   $ft = CountTrue "rules_type"
$rs = CountTrue "raw_sev";    $fs = CountTrue "rules_sev"
$rl = CountTrue "raw_loc";    $fl = CountTrue "rules_loc"
$rd = CountTrue "raw_dt";     $fd = CountTrue "rules_dt"
$hallRaw   = ($results | Measure-Object -Property halluc -Sum).Sum
$hallRules = ($results | Measure-Object -Property rules_halluc -Sum).Sum
$avg  = [math]::Round(($results | Measure-Object -Property seconds -Average).Average, 2)
$rawOverall   = [math]::Round(100.0 * ($rt + $rs + $rl + $rd) / (4 * $n), 1)
$rulesOverall = [math]::Round(100.0 * ($ft + $fs + $fl + $fd) / (4 * $n), 1)

Write-Host ""
Write-Host "================ RESULTS ($Model, $n cases) ================" -ForegroundColor Green
Write-Host ("Valid JSON           : {0}%" -f (Pct $json))
Write-Host ("Incident type        : {0}%  raw  ->  {1}%  with rules" -f (Pct $rt), (Pct $ft))
Write-Host ("Severity             : {0}%  raw  ->  {1}%  with rules" -f (Pct $rs), (Pct $fs))
Write-Host ("Location             : {0}%  raw  ->  {1}%  with rules" -f (Pct $rl), (Pct $fl))
Write-Host ("Date/time            : {0}%  raw  ->  {1}%  with rules" -f (Pct $rd), (Pct $fd))
Write-Host ("OVERALL field acc.   : {0}%  raw  ->  {1}%  with rules" -f $rawOverall, $rulesOverall)
Write-Host ("Hallucinated fields  : {0}  raw  ->  {1}  with rules" -f $hallRaw, $hallRules)
Write-Host ("Avg time per run     : {0}s  (includes model load; app loads model once)" -f $avg)

Write-Host ""
Write-Host "Per category (with rules):" -ForegroundColor Green
$results | Group-Object category | ForEach-Object {
  $g = $_.Group; $k = $g.Count
  $ok = @($g | Where-Object { $_.rules_type -and $_.rules_sev -and $_.rules_loc -and $_.rules_dt }).Count
  Write-Host ("  {0,-14} {1}/{2} fully correct" -f $_.Name, $ok, $k)
}

Write-Host ""
Write-Host "Cases with any wrong field (with rules):" -ForegroundColor Yellow
$results | Where-Object { -not ($_.rules_type -and $_.rules_sev -and $_.rules_loc -and $_.rules_dt) } | ForEach-Object {
  Write-Host ("  #{0} [{1}] {2}" -f $_.id, $_.category, $_.text)
  Write-Host ("      model : {0}" -f $_.output) -ForegroundColor DarkGray
  Write-Host ("      rules : {0}" -f $_.fixed) -ForegroundColor DarkGray
}

$stamp = Get-Date -Format "yyyyMMdd_HHmmss"
$csv = Join-Path $PSScriptRoot "results_${Model}_$stamp.csv"
$results | Export-Csv -NoTypeInformation -Encoding UTF8 $csv
Write-Host ""
Write-Host "Saved detailed results to $csv" -ForegroundColor Cyan
