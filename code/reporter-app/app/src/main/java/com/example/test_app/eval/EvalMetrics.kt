package com.example.test_app.eval

import com.example.test_app.dialogue.DialogueConfig
import com.example.test_app.dialogue.DialogueMode
import com.example.test_app.dialogue.QuestionBank

/** One spoken line as the invariant check sees it (a plain copy of `SpokenLine`, so the check needs no dialogue classes). */
data class SpokenObs(val kind: String, val text: String, val body: String, val field: String?, val mode: String)

/**
 * Spec v2 section 12 scenario 14 as a check on a finished dialogue: every violated rule is returned as a message
 * (an empty list = fine). Pure Kotlin.
 *
 * Rules: each field asked at most once and `asked` equals the fields of the spoken questions; no line spoken twice;
 * greeting, safety check, read-back, opening and defer at most once; every question has exactly one `?`, at most 20 words
 * and does not start with "why"; opening, defer and closing lines contain no `?`; nothing says "notified", "help is on
 * the way" or a retry phrase; the tone mode never decreases.
 */
object DialogueInvariants {
    private val BANNED = listOf("notified", "help is on the way", "didn't catch", "could you repeat", "say that again", "ask again")
    private val ONCE = listOf("GREETING", "SAFETY_CHECK", "READBACK", "OPENING", "DEFER")
    /** The read-back is a statement (ADR-048): only these two kinds may contain a question. */
    private val MAY_ASK = setOf("QUESTION", "SAFETY_CHECK")

    /** Number of times a field was asked beyond the first (the "ask-once violations" metric; must be 0). */
    fun askOnceViolations(asked: List<String>): Int = asked.size - asked.toSet().size

    fun check(lines: List<SpokenObs>, asked: List<String>): List<String> {
        val out = mutableListOf<String>()
        asked.groupingBy { it }.eachCount().filterValues { it > 1 }.forEach { (f, n) -> out += "ask-once: '$f' asked $n times" }
        val questionFields = lines.filter { it.kind == "QUESTION" }.map { it.field }
        if (questionFields != asked) out += "asked fields $asked differ from the spoken questions $questionFields"
        lines.groupingBy { it.text }.eachCount().filterValues { it > 1 }.forEach { (t, n) -> out += "spoken $n times: \"$t\"" }
        for (k in ONCE) if (lines.count { it.kind == k } > 1) out += "$k spoken more than once"
        for (q in lines.filter { it.kind == "QUESTION" }) {
            if (q.text.count { it == '?' } != 1) out += "question needs exactly one '?': \"${q.text}\""
            if (QuestionBank.wordCount(q.body) > QuestionBank.MAX_QUESTION_WORDS) out += "question over ${QuestionBank.MAX_QUESTION_WORDS} words: \"${q.body}\""
            if (q.body.trim().lowercase().startsWith("why")) out += "question starts with why: \"${q.body}\""
        }
        for (l in lines) {
            if (l.kind !in MAY_ASK && l.text.contains('?')) out += "${l.kind} line contains '?': \"${l.text}\""
            val t = l.text.lowercase().replace('\u2019', '\'')
            BANNED.filter { it in t }.forEach { out += "banned phrase \"$it\" in \"${l.text}\"" }
        }
        val questions = lines.count { it.kind == "QUESTION" || it.kind == "SAFETY_CHECK" }
        if (questions > DialogueConfig().maxQuestionsTotal) out += "$questions questions asked (strict cap ${DialogueConfig().maxQuestionsTotal}, safety check included)"
        val modes = lines.map { DialogueMode.fromName(it.mode).ordinal }
        if (modes != modes.sorted()) out += "tone mode decreased during the dialogue"
        return out
    }
}

/**
 * Turns [CaseResult]s into the run summary: ASR (WER), extraction (field accuracy), mapping, dialogue, rephraser and
 * latency metrics. Only cases with status `ok` count; `error` and `skipped` cases are counted separately. Pure Kotlin.
 * The definitions are documented in `eval/README.md` and `doc/TESTING.md`.
 */
object EvalMetrics {

    fun summarize(cases: List<CaseResult>, asrAvailable: Boolean, asrNote: String): EvalSummary {
        val ok = cases.filter { it.ok }
        return EvalSummary(
            casesTotal = cases.size,
            casesOk = ok.size,
            casesError = cases.count { it.status == CaseResult.STATUS_ERROR },
            casesSkipped = cases.count { it.status == CaseResult.STATUS_SKIPPED },
            asr = asr(ok, asrAvailable, asrNote),
            extraction = extraction(ok),
            mapping = mapping(ok),
            dialogue = dialogue(ok),
            rephraser = rephraser(ok),
            latency = latency(ok),
        )
    }

    fun asr(ok: List<CaseResult>, available: Boolean, note: String): AsrSummary {
        if (!available) return AsrSummary(false, "n/a (no ASR)")
        val perCase = ok.mapNotNull { it.werTotal }
        return AsrSummary(
            available = true, note = note, cases = perCase.size,
            utterances = ok.sumOf { c -> c.utterances.count { it.wer != null } },
            meanWer = Wer.mean(perCase), corpusWer = Wer.corpus(perCase),
        )
    }

    fun extraction(ok: List<CaseResult>): ExtractionSummary {
        val fields = ok.flatMap { it.fields }
        fun count(level: MatchLevel) = fields.count { it.match == level.name }
        val perField = fields.groupBy { it.field }.toSortedMap().mapValues { (_, l) ->
            FieldAccuracy(
                total = l.size, matched = l.count { it.match != MatchLevel.NONE.name },
                exact = l.count { it.match == MatchLevel.EXACT.name },
                normalized = l.count { it.match == MatchLevel.NORMALIZED.name },
                relaxed = l.count { it.match == MatchLevel.RELAXED.name },
            )
        }
        val matched = fields.count { it.match != MatchLevel.NONE.name }
        val empties = ok.flatMap { it.emptyChecks }
        return ExtractionSummary(
            total = fields.size, matched = matched, exact = count(MatchLevel.EXACT), normalized = count(MatchLevel.NORMALIZED),
            relaxed = count(MatchLevel.RELAXED), accuracy = if (fields.isEmpty()) null else matched.toDouble() / fields.size,
            perField = perField, emptyChecks = empties.size, emptyCorrect = empties.count { it.actual.isNullOrBlank() },
        )
    }

    fun mapping(ok: List<CaseResult>): MappingSummary {
        val fields = ok.flatMap { it.fields }
        fun count(p: Placement) = fields.count { it.placement == p.name }
        val correct = count(Placement.CORRECT)
        val misplaced = count(Placement.MISPLACED)
        return MappingSummary(correct, misplaced, count(Placement.WRONG), count(Placement.MISSING), Mapping.placementRate(correct, misplaced))
    }

    fun dialogue(ok: List<CaseResult>): DialogueSummary {
        val questions = ok.map { it.asked.size }
        val mix = sortedMapOf<String, MutableMap<String, Int>>()
        for (t in ok.flatMap { it.turns }) {
            val m = mix.getOrPut(t.field) { sortedMapOf() }
            val key = t.outcome ?: "none"
            m[key] = (m[key] ?: 0) + 1
        }
        val withMode = ok.filter { it.modeOk != null }
        val withAsked = ok.filter { it.askedOk != null }
        val checks = ok.flatMap { it.outcomeChecks }
        val withResult = ok.filter { it.expectedResult != null }
        return DialogueSummary(
            reports = ok.size,
            questionsTotal = questions.sum(),
            questionsPerReport = if (ok.isEmpty()) null else questions.average(),
            questionsMax = questions.maxOrNull() ?: 0,
            outcomeMix = mix,
            modeTotal = withMode.size, modeCorrect = withMode.count { it.modeOk == true },
            askedTotal = withAsked.size, askedOk = withAsked.count { it.askedOk == true },
            outcomeTotal = checks.size, outcomeCorrect = checks.count { it.expected == it.actual },
            flowTotal = withResult.size, flowOk = withResult.count { it.resultOk },
            askOnceViolations = ok.sumOf { it.askOnceViolations },
            invariantViolations = ok.sumOf { it.invariantViolations.size },
        )
    }

    /** Rephraser metrics: every turn with a validator verdict is one attempt (`pass`, `fail:<reason>` or `timeout`). */
    fun rephraser(ok: List<CaseResult>): RephraserSummary {
        val attempts = ok.flatMap { it.turns }.filter { it.validator != null }
        val passed = attempts.count { it.validator == "pass" }
        val reasons = sortedMapOf<String, Int>()
        for (a in attempts) {
            val v = a.validator ?: continue
            if (v == "pass") continue
            val reason = v.removePrefix("fail:").substringBefore(':')
            reasons[reason] = (reasons[reason] ?: 0) + 1
        }
        return RephraserSummary(
            attempts = attempts.size, passed = passed,
            passRate = if (attempts.isEmpty()) null else passed.toDouble() / attempts.size,
            rejectionReasons = reasons, latency = Stats.of(attempts.mapNotNull { it.rephraseMs }),
        )
    }

    fun latency(ok: List<CaseResult>): LatencySummary {
        val calls = ok.flatMap { it.llmCalls }
        return LatencySummary(
            asr = Stats.of(ok.flatMap { c -> c.utterances.mapNotNull { it.asrMs } }),
            prefill = Stats.of(calls.mapNotNull { it.prefillMs }),
            decode = Stats.of(calls.mapNotNull { it.decodeMs }),
            total = Stats.of(ok.mapNotNull { it.firstFormMs }),
        )
    }
}
