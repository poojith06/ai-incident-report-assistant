# tests.ps1 - load with:  . .\tests.ps1   (from the platform-tools folder)
# Test-Qwen   : extraction test on the board (UTF-8, works for English, Hindi, Telugu script)
# Test-Update : follow-up / correction test on the board

function Send-Prompt($prompt, $file) {
  [IO.File]::WriteAllText("$PWD\$file", $prompt, (New-Object System.Text.UTF8Encoding($false)))
  .\adb push $file /data/local/tmp/qwen4b/ | Out-Null
  .\adb shell "source /data/local/tmp/setup_qairt_env.sh > /dev/null && cd /data/local/tmp/qwen4b && genie-t2t-run -c genie_config.json --prompt_file $file" | Select-String "BEGIN"
}

function Test-Qwen($text) {
$prompt = @"
<|im_start|>system
Extract incident report fields as JSON in English. Use null for anything not said. Never invent details.
Copy location, datetime and persons_involved using the user's exact words, translated to English if needed.
incident_type: Water Leak, Fire, Injury, Accident, Equipment Failure, Electrical Hazard, Gas Leak, Spill, Security, Other, or null if no incident. If a person got hurt, use Injury.
severity: Critical (deaths, many injured, emergency needed), High (anyone hurt, fire, gas), Medium (contained or isolated), Low (minor), or null if no incident.<|im_end|>
<|im_start|>user
There was a water leak near Conference Room 2 around 10 AM. Maintenance has already isolated the area.<|im_end|>
<|im_start|>assistant
{"incident_type": "Water Leak", "severity": "Medium", "location": "Conference Room 2", "datetime": "around 10 AM", "persons_involved": null, "actions_taken": "Maintenance isolated the area", "description": "Water leak near Conference Room 2, area isolated."}<|im_end|>
<|im_start|>user
uh a worker cut his hand on the machine in the workshop just now<|im_end|>
<|im_start|>assistant
{"incident_type": "Injury", "severity": "High", "location": "workshop", "datetime": "just now", "persons_involved": "a worker", "actions_taken": null, "description": "A worker cut his hand on a machine in the workshop."}<|im_end|>
<|im_start|>user
Good morning, is this working?<|im_end|>
<|im_start|>assistant
{"incident_type": null, "severity": null, "location": null, "datetime": null, "persons_involved": null, "actions_taken": null, "description": null}<|im_end|>
<|im_start|>user
$text<|im_end|>
<|im_start|>assistant
{"incident_type":
"@
Send-Prompt $prompt "qwen_test.txt"
}

function Test-Update($form, $asked, $text) {
$prompt = @"
<|im_start|>system
You update an incident report form. You get CURRENT_FORM, the field the app ASKED about, and what the USER said.
Return JSON with ONLY the fields the user gave or corrected, in English. Never repeat unchanged fields. Never invent details. If the user gave nothing useful, return {}.<|im_end|>
<|im_start|>user
CURRENT_FORM: {"incident_type": "Water Leak", "location": null, "datetime": "around 10 AM"}
ASKED: location
USER: uh it's in block B second floor near the lift<|im_end|>
<|im_start|>assistant
{"location": "block B second floor near the lift"}<|im_end|>
<|im_start|>user
CURRENT_FORM: {"incident_type": "Fire", "location": "Room 2", "datetime": null}
ASKED: datetime
USER: actually it was room 3 not room 2, and it started like 5 minutes ago<|im_end|>
<|im_start|>assistant
{"location": "room 3", "datetime": "5 minutes ago"}<|im_end|>
<|im_start|>user
CURRENT_FORM: $form
ASKED: $asked
USER: $text<|im_end|>
<|im_start|>assistant
{
"@
Send-Prompt $prompt "upd_test.txt"
}

Write-Host "Loaded: Test-Qwen, Test-Update" -ForegroundColor Green
