package com.example.test_app.eval

import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale

/** Writes a run to disk and renders it as Markdown. Pure JVM (java.io only). */
object EvalReport {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = true
    }

    fun toJson(run: EvalRun): String = json.encodeToString(EvalRun.serializer(), run)

    fun fromJson(text: String): EvalRun = Json { ignoreUnknownKeys = true }.decodeFromString(EvalRun.serializer(), text)

    /** Writes `<baseDir>/<runId>/results.json` and `results.md`; returns the run directory. */
    fun write(run: EvalRun, baseDir: File): File {
        val dir = File(baseDir, run.runId).apply { mkdirs() }
        File(dir, "results.json").writeText(toJson(run), Charsets.UTF_8)
        File(dir, "results.md").writeText(markdown(run), Charsets.UTF_8)
        return dir
    }

    private fun pct(x: Double?) = if (x == null) "n/a" else String.format(Locale.US, "%.1f%%", x * 100)
    private fun num(x: Double?) = if (x == null) "n/a" else String.format(Locale.US, "%.1f", x)
    private fun ms(x: Double) = String.format(Locale.US, "%.0f", x)

    /** A few lines for the developer menu. */
    fun headline(run: EvalRun): List<String> {
        val s = run.summary
        return listOf(
            "Run ${run.runId} (${run.input} input, ${run.meta.aiMode} AI)",
            "${s.casesTotal} cases: ${s.casesOk} ok, ${s.casesError} error, ${s.casesSkipped} skipped",
            "Field accuracy: ${pct(s.extraction.accuracy)} (${s.extraction.matched}/${s.extraction.total})",
            "Placement rate: ${pct(s.mapping.placementRate)}",
            "Mode accuracy: ${s.dialogue.modeCorrect}/${s.dialogue.modeTotal}; asked fields ok: ${s.dialogue.askedOk}/${s.dialogue.askedTotal}",
            "Questions per report: ${num(s.dialogue.questionsPerReport)}; ask-once violations: ${s.dialogue.askOnceViolations}",
            "WER: " + if (s.asr.available) "${pct(s.asr.meanWer)} mean, ${pct(s.asr.corpusWer)} corpus" else s.asr.note,
            "Total (speech end to form), median / p90 ms: " + (s.latency.total?.let { "${ms(it.median)} / ${ms(it.p90)}" } ?: "n/a"),
        ) + if (run.meta.aiMode == "mock") listOf("Mock AI: extraction is scripted, these numbers say nothing about the models.") else emptyList()
    }

    /** One Markdown table per metric plus a per-case table. */
    fun markdown(run: EvalRun): String = buildString {
        val s = run.summary
        val m = run.meta
        appendLine("### Run ${run.runId}")
        appendLine()
        appendLine("Started ${run.startedAt}; input: ${run.input}; device: ${m.device} (Android ${m.androidVersion}); app ${m.appVersion}; AI: ${m.aiMode} (${m.llmModel}); ASR: ${m.asrModel}; rephraser: ${if (m.rephraseEnabled) "on" else "off"}.")
        appendLine()
        appendLine("Cases: ${s.casesTotal} (ok ${s.casesOk}, error ${s.casesError}, skipped ${s.casesSkipped}).")
        appendLine()

        appendLine("**ASR (word error rate)**")
        appendLine()
        if (s.asr.available) {
            appendLine("| Cases | Utterances | Mean WER | Corpus WER |")
            appendLine("|---|---|---|---|")
            appendLine("| ${s.asr.cases} | ${s.asr.utterances} | ${pct(s.asr.meanWer)} | ${pct(s.asr.corpusWer)} |")
        } else {
            appendLine("WER: ${s.asr.note}")
        }
        appendLine()

        appendLine("**Extraction (field accuracy; exact / normalised / relaxed match)**")
        appendLine()
        appendLine("| Field | Expected | Matched | Exact | Normalised | Relaxed | Accuracy |")
        appendLine("|---|---|---|---|---|---|---|")
        for ((f, a) in s.extraction.perField) appendLine("| $f | ${a.total} | ${a.matched} | ${a.exact} | ${a.normalized} | ${a.relaxed} | ${pct(a.accuracy)} |")
        val e = s.extraction
        appendLine("| **all** | ${e.total} | ${e.matched} | ${e.exact} | ${e.normalized} | ${e.relaxed} | ${pct(e.accuracy)} |")
        appendLine()
        appendLine("Fields that had to stay empty: ${e.emptyCorrect}/${e.emptyChecks} were empty.")
        appendLine()

        appendLine("**Mapping (value in the right field)**")
        appendLine()
        appendLine("| Correct | Misplaced | Wrong | Missing | Placement rate |")
        appendLine("|---|---|---|---|---|")
        appendLine("| ${s.mapping.correct} | ${s.mapping.misplaced} | ${s.mapping.wrong} | ${s.mapping.missing} | ${pct(s.mapping.placementRate)} |")
        appendLine()

        val d = s.dialogue
        appendLine("**Dialogue**")
        appendLine()
        appendLine("| Reports | Questions per report | Max questions | Mode accuracy | Asked fields ok | Outcomes ok | Flow ok | Ask-once violations | Invariant violations |")
        appendLine("|---|---|---|---|---|---|---|---|---|")
        appendLine(
            "| ${d.reports} | ${num(d.questionsPerReport)} | ${d.questionsMax} | ${d.modeCorrect}/${d.modeTotal} | ${d.askedOk}/${d.askedTotal} | " +
                "${d.outcomeCorrect}/${d.outcomeTotal} | ${d.flowOk}/${d.flowTotal} | ${d.askOnceViolations} | ${d.invariantViolations} |",
        )
        appendLine()
        val outcomes = d.outcomeMix.values.flatMap { it.keys }.toSortedSet()
        if (outcomes.isNotEmpty()) {
            appendLine("Outcome mix per asked field:")
            appendLine()
            appendLine("| Field | " + outcomes.joinToString(" | ") + " |")
            appendLine("|---|" + "---|".repeat(outcomes.size))
            for ((f, mix) in d.outcomeMix) appendLine("| $f | " + outcomes.joinToString(" | ") { (mix[it] ?: 0).toString() } + " |")
            appendLine()
        }

        val r = s.rephraser
        appendLine("**Rephraser**")
        appendLine()
        appendLine("| Attempts | Validator pass rate | Rejection reasons | Latency median / p90 ms |")
        appendLine("|---|---|---|---|")
        appendLine(
            "| ${r.attempts} | ${pct(r.passRate)} | ${if (r.rejectionReasons.isEmpty()) "-" else r.rejectionReasons.entries.joinToString(", ") { "${it.key} x${it.value}" }} | " +
                (r.latency?.let { "${ms(it.median)} / ${ms(it.p90)}" } ?: "n/a") + " |",
        )
        appendLine()

        appendLine("**Latency (ms)**")
        appendLine()
        appendLine("| Stage | Count | Median | p90 | Mean | Min | Max |")
        appendLine("|---|---|---|---|---|---|---|")
        for ((name, st) in listOf(
            "ASR" to s.latency.asr, "Prefill" to s.latency.prefill, "Decode" to s.latency.decode, "Total (speech end to form)" to s.latency.total,
        )) {
            if (st == null) appendLine("| $name | 0 | n/a | n/a | n/a | n/a | n/a |")
            else appendLine("| $name | ${st.count} | ${ms(st.median)} | ${ms(st.p90)} | ${ms(st.mean)} | ${ms(st.min)} | ${ms(st.max)} |")
        }
        appendLine()

        appendLine("**Cases**")
        appendLine()
        appendLine("| Case | Status | Mode | Asked | Fields matched | Ask-once | Notes |")
        appendLine("|---|---|---|---|---|---|---|")
        for (c in run.cases) {
            val matched = c.fields.count { it.match != MatchLevel.NONE.name }
            val mode = if (c.ok) "${c.mode}" + (c.modeOk?.let { if (it) " ok" else " (expected ${c.expectedMode})" } ?: "") else "-"
            val asked = if (c.ok) c.asked.joinToString(",").ifEmpty { "-" } + (c.askedOk?.let { if (it) " ok" else " MISMATCH" } ?: "") else "-"
            val notes = c.detail ?: c.invariantViolations.joinToString("; ")
            appendLine("| ${c.id} | ${c.status} | $mode | $asked | $matched/${c.fields.size} | ${c.askOnceViolations} | ${notes.replace("|", "/")} |")
        }
    }
}
