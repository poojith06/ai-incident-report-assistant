package com.example.incident

/*
 * Self-check using real Qwen3-4B outputs captured on the QIDK board.
 * Run on a laptop:  kotlinc IncidentRules.kt IncidentRulesCheck.kt -include-runtime -d check.jar && java -jar check.jar
 * Or move the cases into a JUnit test in the Android project.
 */

private var pass = 0
private var fail = 0

private fun check(name: String, cond: Boolean, detail: String) {
    if (cond) pass++ else fail++
    println((if (cond) "PASS  " else "FAIL  ") + name + if (cond) "" else "\n      $detail")
}

private fun case(name: String, transcript: String, raw: String,
                 type: String?, severity: String?, location: String?, datetime: String?,
                 emergency: Boolean? = null) {
    val f = RuleEngine.fromModelOutput(raw, transcript)
    val ok = f.incidentType == type && f.severity == severity &&
        f.location == location && f.datetime == datetime &&
        (emergency == null || f.emergency == emergency)
    check(name, ok, "got type=${f.incidentType} sev=${f.severity} loc=${f.location} time=${f.datetime} emergency=${f.emergency}")
}

fun main() {
    println("== Extraction rules (real model outputs) ==")
    case("slip: Accident/Medium -> Injury/High, snap 'catering'",
        "Umm so like someone slipped near the cafeteria stairs, I think he hurt his ankle, maybe 15 minutes ago.",
        " \"Accident\", \"severity\": \"Medium\", \"location\": \"catering stairs\", \"datetime\": \"around 15 minutes ago\", \"persons_involved\": \"someone\", \"actions_taken\": null}",
        "Injury", "High", "cafeteria stairs", "around 15 minutes ago")

    case("fatal accident: request moved out, emergency on",
        "There was a fatal accident near the highway and many people are in serious condition. Need emergency services immediately.",
        " \"Accident\", \"severity\": \"Critical\", \"location\": \"near the highway\", \"datetime\": null, \"persons_involved\": null, \"actions_taken\": \"need emergency services immediately\"}",
        "Accident", "Critical", "near the highway", null, emergency = true)

    case("datetime junk 'please come fast' -> null",
        "fire fire in the canteen kitchen please come fast",
        " \"Fire\", \"severity\": \"Critical\", \"location\": \"canteen kitchen\", \"datetime\": \"please come fast\", \"persons_involved\": null, \"actions_taken\": null}",
        "Fire", "High", "canteen kitchen", null)

    case("'not specified' + 'not breathing' -> Injury/Critical",
        "Two workers fell from the scaffolding, one is not breathing, call an ambulance.",
        " \"Accident\", \"severity\": \"Critical\", \"location\": \"scaffolding\", \"datetime\": \"not specified\", \"persons_involved\": \"two workers\", \"actions_taken\": \"call an ambulance\"}",
        "Injury", "Critical", "scaffolding", null, emergency = true)

    case("invented type 'Security' -> Other",
        "Something happened in the parking area.",
        " \"Security\", \"severity\": \"Low\", \"location\": \"parking area\", \"datetime\": null, \"persons_involved\": null, \"actions_taken\": null}",
        "Other", "Low", "parking area", null)

    case("stuck lift: Accident -> Equipment Failure, cap Critical",
        "The lift is stuck between the second and third floor with people inside since 5 minutes.",
        " \"Accident\", \"severity\": \"Critical\", \"location\": \"between the second and third floor\", \"datetime\": \"5 minutes ago\", \"persons_involved\": \"people inside\", \"actions_taken\": \"lift stuck\"}",
        "Equipment Failure", "High", "between the second and third floor", "5 minutes ago")

    case("burst pipe Critical -> High",
        "water pipe burst in the basement at 6 am today, water everywhere",
        " \"Water Leak\", \"severity\": \"Critical\", \"location\": \"basement\", \"datetime\": \"6 am today\", \"persons_involved\": null, \"actions_taken\": \"water everywhere\"}",
        "Water Leak", "High", "basement", "6 am today")

    case("small fire Low -> Medium (hazard floor)",
        "There was a small fire in the dustbin near the canteen but we put it out with an extinguisher.",
        " \"Fire\", \"severity\": \"Low\", \"location\": \"near the canteen\", \"datetime\": null, \"persons_involved\": null, \"actions_taken\": \"put out with an extinguisher\"}",
        "Fire", "Medium", "near the canteen", null)

    case("negation: 'no one was hurt' stays non-injury",
        "The projector in the seminar hall exploded with a loud bang, no one was hurt.",
        " \"Fire\", \"severity\": \"Low\", \"location\": \"seminar hall\", \"datetime\": null, \"persons_involved\": null, \"actions_taken\": null}",
        "Fire", "Medium", "seminar hall", null)

    case("non-incident stays empty",
        "Hello, can you hear me?",
        " null, \"severity\": null, \"location\": null, \"datetime\": null, \"persons_involved\": null, \"actions_taken\": null}",
        null, null, null, null)

    println("\n== Telugu / Hindi safety net (model returned no incident) ==")
    case("Telugu fire 'nippu' not dropped",
        "lab lo nippu antukundi, tondaraga randi",
        " null, \"severity\": null, \"location\": null, \"datetime\": null, \"persons_involved\": null, \"actions_taken\": null}",
        "Fire", "High", null, null, emergency = true)

    case("Telugu gas smell not dropped",
        "kitchen lo gas vasana vastundi",
        " null, \"severity\": null, \"location\": null, \"datetime\": null, \"persons_involved\": null, \"actions_taken\": null}",
        "Gas Leak", "High", null, null)

    case("Telugu injury 'debba' not dropped",
        "canteen daggara oka abbayi padipoyadu, kaaliki debba tagilindi, 10 nimishala kritham",
        " null, \"severity\": null, \"location\": null, \"datetime\": null, \"persons_involved\": null, \"actions_taken\": null}",
        "Injury", "High", null, null)

    case("Telugu collision, nobody hurt -> Accident, not Injury",
        "parking lo rendu cars dhee kottukunnayi, evvariki em kaaledu",
        " null, \"severity\": null, \"location\": \"parking lo rendu cars dhee kottukunnayi\", \"datetime\": null, \"persons_involved\": null, \"actions_taken\": null}",
        "Accident", "Medium", "parking lo rendu cars dhee kottukunnayi", null)

    println("\n== Follow-up loop ==")
    FormManager.reset()
    val f = RuleEngine.fromModelOutput(
        " \"Injury\", \"severity\": \"High\", \"location\": null, \"datetime\": null, \"persons_involved\": null, \"actions_taken\": null}",
        "someone slipped and hurt his ankle")
    val q1 = FormManager.nextQuestion(f)
    check("asks location first", q1?.field == Fields.LOCATION, "got $q1")
    FormManager.mergeAnswer(f, Fields.LOCATION, "\"location\": \"near the cafeteria stairs\"}", "near the cafeteria stairs")
    check("location merged", f.location == "near the cafeteria stairs", "got ${f.location}")
    val q2 = FormManager.nextQuestion(f)
    check("then asks time", q2?.field == Fields.DATETIME, "got $q2")
    FormManager.mergeAnswer(f, Fields.DATETIME, "}", "I don't know")
    check("'I don't know' keeps time empty", f.datetime == null, "got ${f.datetime}")
    FormManager.nextQuestion(f) // asked a 2nd time
    FormManager.mergeAnswer(f, Fields.DATETIME, "}", "no idea")
    val q3 = FormManager.nextQuestion(f)
    check("stops after 2 asks, marks not provided", q3 == null && f.datetime == NOT_PROVIDED, "got $q3 / ${f.datetime}")
    check("description built from verified fields",
        f.description == "Injury at near the cafeteria stairs, Not provided." || f.description.startsWith("Injury at"),
        "got ${f.description}")

    println("\n== Prompt sanity ==")
    val p = Prompts.extraction("test")
    check("prompt ends with prefill", p.endsWith(Prompts.EXTRACTION_PREFILL), p.takeLast(40))

    println("\n$pass passed, $fail failed")
}
