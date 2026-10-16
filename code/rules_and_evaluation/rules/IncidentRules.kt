package com.example.incident

/*
 * Incident report rule layer.
 * The LLM proposes field values; this code verifies them against what the user actually said.
 * Pure Kotlin, no Android dependencies, so it can be unit-tested anywhere.
 *
 * Typical use in the app:
 *   val prompt = Prompts.extraction(transcript)            // send to Genie
 *   val raw    = llm.generate(prompt)                      // model text after the prefill
 *   val form   = RuleEngine.fromModelOutput(raw, transcript)
 *   val next   = FormManager.nextQuestion(form)            // null -> go to review screen
 *   ... user answers ...
 *   val raw2   = llm.generate(Prompts.update(form, next.field, answer))
 *   FormManager.mergeAnswer(form, next.field, raw2, answer)
 */

// ----------------------------------------------------------------------------- Data

data class IncidentForm(
    var incidentType: String? = null,
    var severity: String? = null,
    var location: String? = null,
    var datetime: String? = null,
    var personsInvolved: String? = null,
    var actionsTaken: String? = null,
    var emergency: Boolean = false,
    val transcripts: MutableList<String> = mutableListOf()
) {
    /** Built from verified fields only, so it can never contain invented details. */
    val description: String
        get() {
            if (incidentType == null) return ""
            val parts = mutableListOf(incidentType!!)
            location?.let { parts[0] = "${parts[0]} at $it" }
            datetime?.let { parts[0] = "${parts[0]}, $it" }
            personsInvolved?.let { parts.add("Person(s) involved: $it") }
            actionsTaken?.let { parts.add("Action taken: $it") }
            return parts.joinToString(". ") + "."
        }
}

object Fields {
    const val TYPE = "incident_type"
    const val SEVERITY = "severity"
    const val LOCATION = "location"
    const val DATETIME = "datetime"
    const val PERSONS = "persons_involved"
    const val ACTIONS = "actions_taken"
    val MANDATORY = listOf(TYPE, LOCATION, DATETIME, SEVERITY)
}

// ----------------------------------------------------------------------------- Prompts (Qwen3 chat format)

object Prompts {
    const val EXTRACTION_PREFILL = "{\"incident_type\":"
    const val UPDATE_PREFILL = "{"

    private const val SYSTEM =
        "Extract incident report fields as JSON in English. Use null for anything not said. Never invent details.\n" +
        "Copy location, datetime and persons_involved using the user's exact words, translated to English if needed.\n" +
        "incident_type: Water Leak, Fire, Injury, Accident, Equipment Failure, Electrical Hazard, Gas Leak, Spill, Security, Other, or null if no incident. If a person got hurt, use Injury.\n" +
        "severity: Critical (deaths, many injured, emergency needed), High (anyone hurt, fire, gas), Medium (contained or isolated), Low (minor), or null if no incident."

    private val SHOTS = listOf(
        "There was a water leak near Conference Room 2 around 10 AM. Maintenance has already isolated the area." to
            "{\"incident_type\": \"Water Leak\", \"severity\": \"Medium\", \"location\": \"Conference Room 2\", \"datetime\": \"around 10 AM\", \"persons_involved\": null, \"actions_taken\": \"Maintenance isolated the area\"}",
        "uh a worker cut his hand on the machine in the workshop just now" to
            "{\"incident_type\": \"Injury\", \"severity\": \"High\", \"location\": \"workshop\", \"datetime\": \"just now\", \"persons_involved\": \"a worker\", \"actions_taken\": null}",
        "Good morning, is this working?" to
            "{\"incident_type\": null, \"severity\": null, \"location\": null, \"datetime\": null, \"persons_involved\": null, \"actions_taken\": null}"
    )

    fun extraction(transcript: String): String = buildString {
        append("<|im_start|>system\n").append(SYSTEM).append("<|im_end|>\n")
        for ((u, a) in SHOTS) {
            append("<|im_start|>user\n").append(u).append("<|im_end|>\n")
            append("<|im_start|>assistant\n").append(a).append("<|im_end|>\n")
        }
        append("<|im_start|>user\n").append(transcript.trim()).append("<|im_end|>\n")
        append("<|im_start|>assistant\n").append(EXTRACTION_PREFILL)
    }

    fun update(form: IncidentForm, askedField: String, answer: String): String = buildString {
        append("<|im_start|>system\n")
        append("You update an incident report form. You get CURRENT_FORM, the field the app ASKED about, and what the USER said.\n")
        append("Return JSON with ONLY the fields the user gave or corrected, in English. Never repeat unchanged fields. Never invent details. If the user gave nothing useful, return {}.<|im_end|>\n")
        append("<|im_start|>user\nCURRENT_FORM: {\"incident_type\": \"Water Leak\", \"location\": null, \"datetime\": \"around 10 AM\"}\nASKED: location\nUSER: uh it's in block B second floor near the lift<|im_end|>\n")
        append("<|im_start|>assistant\n{\"location\": \"block B second floor near the lift\"}<|im_end|>\n")
        append("<|im_start|>user\nCURRENT_FORM: {\"incident_type\": \"Fire\", \"location\": \"Room 2\", \"datetime\": null}\nASKED: datetime\nUSER: actually it was room 3 not room 2, and it started like 5 minutes ago<|im_end|>\n")
        append("<|im_start|>assistant\n{\"location\": \"room 3\", \"datetime\": \"5 minutes ago\"}<|im_end|>\n")
        append("<|im_start|>user\nCURRENT_FORM: ").append(formToJson(form))
        append("\nASKED: ").append(askedField).append("\nUSER: ").append(answer.trim()).append("<|im_end|>\n")
        append("<|im_start|>assistant\n").append(UPDATE_PREFILL)
    }

    private fun q(v: String?) = if (v == null) "null" else "\"" + v.replace("\"", "'") + "\""
    fun formToJson(f: IncidentForm) =
        "{\"incident_type\": ${q(f.incidentType)}, \"severity\": ${q(f.severity)}, \"location\": ${q(f.location)}, \"datetime\": ${q(f.datetime)}}"
}

// ----------------------------------------------------------------------------- Tolerant JSON parsing

object ModelOutput {
    /**
     * Turns the model's raw text (which starts right after the prefill) into a field map.
     * Strips Genie markers, cuts at the first balanced closing brace, never throws.
     */
    fun parse(raw: String, prefill: String): Map<String, Any?>? {
        var body = raw
        body.indexOf("[BEGIN]:").takeIf { it >= 0 }?.let { body = body.substring(it + 8) }
        body = body.substringBefore("[END]").substringBefore("<|im_end|>")
        // Some runs repeat the prefilled "{" themselves; don't add it twice.
        val text = if (body.trimStart().startsWith("{")) body.trimStart() else prefill + body
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0; var inStr = false; var esc = false; var end = -1
        for (i in start until text.length) {
            val c = text[i]
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
            when (c) { '"' -> inStr = true; '{' -> depth++; '}' -> { depth--; if (depth == 0) { end = i; break } } }
        }
        val json = if (end > 0) text.substring(start, end + 1) else text.substring(start) + "}"
        return try { MiniJson(json).parseObject() } catch (e: Exception) { null }
    }

    private class MiniJson(val s: String) {
        var i = 0
        fun parseObject(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>(); ws(); expect('{'); ws()
            if (peek() == '}') { i++; return m }
            while (true) {
                ws(); val k = parseString(); ws(); expect(':'); ws(); m[k] = parseValue(); ws()
                when (s[i++]) { ',' -> continue; '}' -> return m; else -> error("bad object") }
            }
        }
        fun parseValue(): Any? = when (peek()) {
            '"' -> parseString()
            '{' -> parseObject()
            '[' -> { i++; val l = mutableListOf<Any?>(); ws()
                     if (peek() == ']') { i++ } else { while (true) { ws(); l.add(parseValue()); ws(); if (s[i++] == ']') break } }
                     l }
            else -> { val st = i; while (i < s.length && s[i] !in ",}] \n\r\t") i++
                      when (val w = s.substring(st, i)) { "null" -> null; "true" -> true; "false" -> false; else -> w } }
        }
        fun parseString(): String {
            expect('"'); val sb = StringBuilder()
            while (s[i] != '"') { if (s[i] == '\\') { i++; sb.append(when (s[i]) { 'n' -> '\n'; 't' -> '\t'; else -> s[i] }) } else sb.append(s[i]); i++ }
            i++; return sb.toString()
        }
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun peek() = s[i]
        fun expect(c: Char) { if (s[i] != c) error("expected $c at $i"); i++ }
    }
}

// ----------------------------------------------------------------------------- Rules

const val NOT_PROVIDED = "Not provided"

object RuleEngine {
    val TYPES = listOf("Water Leak", "Fire", "Injury", "Accident", "Equipment Failure",
        "Electrical Hazard", "Gas Leak", "Spill", "Security", "Other")
    private val SEVERITIES = listOf("Low", "Medium", "High", "Critical")
    private val FILLERS = setOf("", "null", "none", "unknown", "n/a", "na", "not specified", "not mentioned",
        "unspecified", "not provided", "not given", "someone", "a person", "people")

    // English + Hindi/Hinglish + Telugu (romanized) keywords. Order matters only for the safety net.
    private val TYPE_WORDS: Map<String, List<String>> = linkedMapOf(
        "Fire" to listOf("fire", "smoke", "flame", "explod", "blast", "bang", "aag", "jal gay", "nippu", "nipu", "mantalu"),
        "Gas Leak" to listOf("gas"),
        "Injury" to listOf("hurt", "injur", "bleed", "blood", "cut ", "fracture", "faint", "collaps", "unconscious",
            "burned", "finger", "wound", "chot", "ghayal", "khoon", "behosh", "debba", "raktham", "padipoyadu"),
        "Water Leak" to listOf("leak", "water", "drip", "pipe", "flood", "burst", "paani", "neellu", "neeru"),
        "Electrical Hazard" to listOf("wire", "spark", "electric", "shock", "switch", "socket", "short circuit", "current"),
        "Accident" to listOf("accident", "hit ", "crash", "collid", "fell", "fall", "slip", "takkar", "gir ", "dhee", "kottuk"),
        "Equipment Failure" to listOf("not working", "broken", "stuck", "fail", "kharab", "machine", "printer",
            "lift", "elevator", "projector", "pani cheyatledu"),
        "Spill" to listOf("spill", "oil", "chemical", "slippery", "glass"),
        "Security" to listOf("stole", "theft", "break-in", "broke into", "intruder", "suspicious", "chori", "fight")
    )
    private val INJURY = TYPE_WORDS.getValue("Injury") + listOf("not breathing", "broke his", "broke her", "ankle", "wrist")
    // Health symptoms raise severity but do not change the incident type (e.g. gas leak + dizzy people).
    private val SYMPTOMS = listOf("dizzy", "vomit", "breathing problem", "difficulty breathing", "can't breathe",
        "cannot breathe", "suffocat", "choking", "chakkar", "saans", "kallu tirugu")
    private val FATAL = listOf("fatal", "dead", "died", "killed", "not breathing", "maut", "mar gaya", "chanipoyaru", "chanipoyadu")
    private val EMERGENCY = FATAL + listOf("ambulance", "emergency", "many people", "serious condition", "trapped",
        "jaldi", "tondaraga", "help")
    private val NEGATIONS = listOf("no one", "nobody", "not hurt", "no injur", "koi ghayal nahi", "koi nahi",
        "evvariki em kaaledu", "evariki emi kaaledu")
    private val REQUEST_WORDS = listOf("need", "please", "call ", "send ", "come fast", "help")
    private val TIME_RE = Regex("\\d|\\bam\\b|\\bpm\\b|ago|now|today|tonight|yesterday|morning|evening|afternoon|night|minute|hour|second|since|just|abhi|subah|kal|shaam|raat|pehle|baje|kritham|poddun|ninna|ippude")
    private val HAZARDS = setOf("Fire", "Gas Leak", "Electrical Hazard")
    private val STOPWORDS = setOf("the", "a", "an", "near", "in", "at", "on", "of", "and", "around", "about",
        "maybe", "his", "her", "their", "to", "from", "by", "is", "was", "it")

    fun fromModelOutput(raw: String, transcript: String): IncidentForm {
        val map = ModelOutput.parse(raw, Prompts.EXTRACTION_PREFILL) ?: emptyMap()
        val form = IncidentForm()
        form.transcripts.add(transcript)
        form.incidentType = normType(str(map[Fields.TYPE]))
        form.severity = normSeverity(str(map[Fields.SEVERITY]))
        form.location = clean(str(map[Fields.LOCATION]))
        form.datetime = clean(str(map[Fields.DATETIME]))
        form.personsInvolved = clean(str(map[Fields.PERSONS]))
        form.actionsTaken = clean(str(map[Fields.ACTIONS]))
        apply(form, transcript)
        return form
    }

    /** All verification rules. Safe to call again after every merge. */
    fun apply(form: IncidentForm, transcript: String) {
        val t = " " + transcript.lowercase() + " "
        val latin = isLatin(transcript)

        // A. Time must look like a time expression.
        form.datetime?.let { if (it != NOT_PROVIDED && !TIME_RE.containsMatchIn(it.lowercase())) form.datetime = null }

        // B. Grounding: location / persons must come from the user's words (only checkable for Latin script).
        if (latin) {
            form.location = form.location?.let { if (it == NOT_PROVIDED) it else snapOrDrop(it, transcript) }
            form.personsInvolved = form.personsInvolved?.let { snapOrDrop(it, transcript) }
        }

        // C. "need an ambulance" is a request, not an action already taken.
        form.actionsTaken?.let { a -> if (REQUEST_WORDS.any { a.lowercase().contains(it) }) { form.actionsTaken = null; form.emergency = true } }

        // D. Type grounding + safety net (never let a real fire / gas / injury become "no incident").
        val hits = TYPE_WORDS.filter { (_, words) -> words.any { t.contains(it) } }.keys.toList()
        val type = form.incidentType
        if (type == null) {
            if (hits.isNotEmpty()) form.incidentType = hits.first()
        } else if (type != "Other" && latin) {
            val grounded = TYPE_WORDS[type]?.any { t.contains(it) } == true
            if (!grounded) form.incidentType = if (hits.size == 1) hits.first() else "Other"
        }

        // E. Injury / fatal rules (skipped when negated, e.g. "no one was hurt").
        val negated = NEGATIONS.any { t.contains(it) }
        if (!negated && INJURY.any { t.contains(it) }) {
            form.incidentType = "Injury"
            if (form.severity != "Critical") form.severity = "High"
        }
        if (!negated && SYMPTOMS.any { t.contains(it) } && rank(form.severity) < 3) form.severity = "High"
        if (FATAL.any { t.contains(it) }) form.severity = "Critical"

        // F. Critical only when justified, otherwise cap at High.
        val emergency = EMERGENCY.any { t.contains(it) }
        if (form.severity == "Critical" && !emergency) form.severity = "High"

        // G. Hazards are at least Medium; known incidents always get a severity.
        if (form.incidentType != null && form.severity == null) form.severity = defaultSeverity(form.incidentType!!)
        if (form.incidentType in HAZARDS && rank(form.severity) < 2) form.severity = "Medium"

        // H. Emergency banner.
        if (form.severity == "Critical" || emergency) form.emergency = true
    }

    // ---- helpers
    private fun str(v: Any?): String? = when (v) {
        null -> null
        is List<*> -> v.filterNotNull().joinToString(", ").ifBlank { null }
        else -> v.toString()
    }
    fun clean(v: String?): String? = v?.trim()?.trim('.', ',')?.takeIf { it.lowercase() !in FILLERS }

    fun normType(v: String?): String? {
        val s = clean(v)?.lowercase() ?: return null
        TYPES.firstOrNull { it.lowercase() == s }?.let { return it }
        return when {
            "gas" in s -> "Gas Leak"
            "fire" in s || "smoke" in s -> "Fire"
            "leak" in s || "water" in s -> "Water Leak"
            "injur" in s || "hurt" in s || "slip" in s || "fall" in s -> "Injury"
            "accident" in s || "collision" in s || "crash" in s -> "Accident"
            "electric" in s || "spark" in s || "wire" in s -> "Electrical Hazard"
            "equipment" in s || "failure" in s || "broken" in s || "malfunction" in s -> "Equipment Failure"
            "spill" in s -> "Spill"
            "security" in s || "theft" in s || "break" in s -> "Security"
            else -> "Other"
        }
    }

    fun normSeverity(v: String?): String? {
        val s = clean(v)?.lowercase() ?: return null
        return SEVERITIES.firstOrNull { s.contains(it.lowercase()) }
    }

    private fun rank(s: String?) = when (s) { "Low" -> 1; "Medium" -> 2; "High" -> 3; "Critical" -> 4; else -> 0 }
    private fun defaultSeverity(type: String) = when (type) {
        "Fire", "Gas Leak", "Injury" -> "High"
        "Electrical Hazard", "Accident", "Security", "Water Leak", "Spill" -> "Medium"
        else -> "Low"
    }

    fun isLatin(s: String) = s.count { it.isLetter() && it.code > 0x024F } < 3

    private fun tokens(s: String) = s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    /**
     * Keeps a value only if it is supported by the transcript.
     * Slightly misheard words are snapped to the user's own words ("catering stairs" -> "cafeteria stairs").
     */
    fun snapOrDrop(value: String, transcript: String): String? {
        val vt = tokens(value).filter { it !in STOPWORDS }
        if (vt.isEmpty()) return null
        val tt = tokens(transcript)
        if (vt.all { v -> tt.any { it == v } }) return value
        // Try to find a span in the transcript that matches the value's content words closely.
        val words = transcript.split(Regex("\\s+")).filter { it.isNotBlank() }
        val n = vt.size
        var best: String? = null; var bestScore = 0.0
        for (len in n..(n + 1)) for (i in 0..(words.size - len).coerceAtLeast(-1)) {
            if (i < 0) continue
            val span = words.subList(i, i + len)
            val st = span.flatMap { tokens(it) }.filter { it !in STOPWORDS }
            if (st.isEmpty()) continue
            val score = vt.sumOf { v -> st.maxOf { similarity(v, it) } } / vt.size
            if (score > bestScore) { bestScore = score; best = span.joinToString(" ").trim(',', '.') }
        }
        if (bestScore >= 0.6 && best != null) return best
        // Lenient for code-mixed speech: keep if at least one content word or number appears.
        return if (vt.any { v -> tt.contains(v) }) value else null
    }

    private fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length)
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        return 1.0 - d[a.length][b.length].toDouble() / maxOf(a.length, b.length)
    }
}

// ----------------------------------------------------------------------------- Follow-up loop

data class FollowUp(val field: String, val question: String)

object FormManager {
    private val QUESTIONS = mapOf(
        Fields.TYPE to "What kind of incident was it?",
        Fields.LOCATION to "Where did this happen?",
        Fields.DATETIME to "When did it happen?",
        Fields.SEVERITY to "How serious is it: low, medium, high, or critical?"
    )
    private val askedCount = mutableMapOf<String, Int>()
    const val MAX_ASKS = 2

    fun reset() = askedCount.clear()

    fun missingFields(f: IncidentForm): List<String> = Fields.MANDATORY.filter {
        when (it) {
            Fields.TYPE -> f.incidentType == null
            Fields.LOCATION -> f.location == null
            Fields.DATETIME -> f.datetime == null
            Fields.SEVERITY -> f.severity == null
            else -> false
        }
    }

    /** Next question to speak, or null when the form is ready for review. Never asks the same thing more than twice. */
    fun nextQuestion(f: IncidentForm): FollowUp? {
        for (field in missingFields(f)) {
            val n = askedCount[field] ?: 0
            if (n < MAX_ASKS) { askedCount[field] = n + 1; return FollowUp(field, QUESTIONS.getValue(field)) }
            markUnknown(f, field)
        }
        return null
    }

    private fun markUnknown(f: IncidentForm, field: String) {
        when (field) {
            Fields.LOCATION -> f.location = NOT_PROVIDED
            Fields.DATETIME -> f.datetime = NOT_PROVIDED
            Fields.TYPE -> f.incidentType = "Other"
            Fields.SEVERITY -> f.severity = "Medium"
        }
    }

    /** Merges the model's update (only changed fields), then re-runs every rule on the combined transcript. */
    fun mergeAnswer(f: IncidentForm, askedField: String, raw: String, answer: String) {
        f.transcripts.add(answer)
        val upd = ModelOutput.parse(raw, Prompts.UPDATE_PREFILL) ?: emptyMap()
        fun s(k: String) = RuleEngine.clean(upd[k]?.toString())
        s(Fields.TYPE)?.let { f.incidentType = RuleEngine.normType(it) }
        s(Fields.SEVERITY)?.let { v -> RuleEngine.normSeverity(v)?.let { f.severity = it } }
        s(Fields.LOCATION)?.let { f.location = it }
        s(Fields.DATETIME)?.let { f.datetime = it }
        s(Fields.PERSONS)?.let { f.personsInvolved = it }
        s(Fields.ACTIONS)?.let { f.actionsTaken = it }
        // Severity answered directly ("high") without the model returning it.
        if (askedField == Fields.SEVERITY && upd[Fields.SEVERITY] == null) RuleEngine.normSeverity(answer)?.let { f.severity = it }
        RuleEngine.apply(f, f.transcripts.joinToString(" . "))
    }
}
