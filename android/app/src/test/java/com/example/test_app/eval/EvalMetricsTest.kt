package com.example.test_app.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatsTest {
    private val eps = 1e-9

    @Test fun oddCount() {
        val s = Stats.of(listOf(50, 10, 30, 20, 40))!!
        assertEquals(5, s.count)
        assertEquals(30.0, s.median, eps)
        assertEquals(50.0, s.p90, eps) // rank ceil(0.9 * 5) = 5
        assertEquals(30.0, s.mean, eps)
        assertEquals(10.0, s.min, eps)
        assertEquals(50.0, s.max, eps)
    }

    @Test fun evenCountMedianIsTheMeanOfTheMiddleTwo() {
        val s = Stats.of(listOf(4, 1, 3, 2))!!
        assertEquals(2.5, s.median, eps)
        assertEquals(4.0, s.p90, eps) // rank ceil(3.6) = 4
    }

    @Test fun p90OfTenValuesIsTheNinth() {
        val s = Stats.of((1..10).toList())!!
        assertEquals(5.5, s.median, eps)
        assertEquals(9.0, s.p90, eps)
        assertEquals(5.5, s.mean, eps)
    }

    @Test fun singleValueAndEmpty() {
        val s = Stats.of(listOf(7))!!
        assertEquals(7.0, s.median, eps)
        assertEquals(7.0, s.p90, eps)
        assertNull(Stats.of(emptyList()))
    }

    @Test fun nearestRankPercentile() {
        val sorted = listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0, 12.0, 13.0, 14.0, 15.0, 16.0, 17.0, 18.0, 19.0, 20.0)
        assertEquals(18.0, Stats.percentile(sorted, 90), eps) // rank 18 of 20
        assertEquals(1.0, Stats.percentile(sorted, 1), eps)
        assertEquals(20.0, Stats.percentile(sorted, 100), eps)
    }
}

class DialogueInvariantsTest {
    private fun line(kind: String, text: String, mode: String = "PLAIN", field: String? = null, body: String = text) =
        SpokenObs(kind, text, body, field, mode)

    private val good = listOf(
        line("GREETING", "Hi, describe your incident."),
        line("OPENING", "Got it. Just a couple of quick details."),
        line("QUESTION", "Was anyone hurt?", field = "injury"),
        line("QUESTION", "Where did this happen?", field = "location"),
        line("READBACK", "Logged: water leak at Conference Room 2. Please review and tap submit."),
    )

    @Test fun aCleanDialoguePasses() {
        assertEquals(emptyList<String>(), DialogueInvariants.check(good, listOf("injury", "location")))
    }

    @Test fun askOnceViolationsAreCounted() {
        assertEquals(0, DialogueInvariants.askOnceViolations(listOf("injury", "location")))
        assertEquals(1, DialogueInvariants.askOnceViolations(listOf("injury", "location", "injury")))
        assertEquals(3, DialogueInvariants.askOnceViolations(listOf("a", "a", "a", "a")))
        val v = DialogueInvariants.check(
            listOf(line("QUESTION", "Was anyone hurt?", field = "injury"), line("QUESTION", "Anyone hurt, please?", field = "injury")),
            listOf("injury", "injury"),
        )
        assertTrue(v.toString(), v.any { it.startsWith("ask-once: 'injury' asked 2 times") })
    }

    @Test fun askedMustEqualTheSpokenQuestions() {
        val v = DialogueInvariants.check(good, listOf("injury"))
        assertTrue(v.toString(), v.any { it.contains("differ from the spoken questions") })
    }

    @Test fun aLineSpokenTwiceIsReported() {
        val v = DialogueInvariants.check(good + line("OPENING", "Got it. Just a couple of quick details."), listOf("injury", "location"))
        assertTrue(v.toString(), v.any { it.startsWith("spoken 2 times") })
        assertTrue(v.toString(), v.any { it == "OPENING spoken more than once" })
    }

    @Test fun questionNeedsExactlyOneQuestionMarkAndAtMost20WordsAndNoWhy() {
        fun check(text: String) = DialogueInvariants.check(listOf(line("QUESTION", text, field = "injury")), listOf("injury"))
        assertTrue(check("Was anyone hurt").any { it.startsWith("question needs exactly one '?'") })
        assertTrue(check("Was anyone hurt? Are you ok?").any { it.startsWith("question needs exactly one '?'") })
        assertTrue(check("Why was anyone hurt?").any { it.startsWith("question starts with why") })
        val twenty = (1..19).joinToString(" ") { "w$it" } + " ok?"
        assertEquals(emptyList<String>(), check(twenty))
        assertTrue(check("$twenty more").size >= 1)
        val twentyOne = (1..20).joinToString(" ") { "w$it" } + " ok?"
        assertTrue(check(twentyOne).any { it.startsWith("question over 20 words") })
    }

    @Test fun typingOfferDoesNotCountTowardsTheBody() {
        val q = line("QUESTION", "Was anyone hurt? You can also type your answer.", field = "injury", body = "Was anyone hurt?")
        assertEquals(emptyList<String>(), DialogueInvariants.check(listOf(q), listOf("injury")))
    }

    @Test fun bannedPhrasesAreReported() {
        for (phrase in listOf("The team has been notified.", "Help is on the way.", "Sorry, I didn't catch that.", "Could you repeat that?")) {
            val v = DialogueInvariants.check(listOf(line("OPENING", phrase)), emptyList())
            assertTrue("$phrase -> $v", v.any { it.startsWith("banned phrase") })
        }
    }

    @Test fun openingLinesMustNotAsk() {
        val v = DialogueInvariants.check(listOf(line("OPENING", "Got it?")), emptyList())
        assertTrue(v.toString(), v.any { it.startsWith("OPENING line contains '?'") })
    }

    @Test fun modeMayOnlyEscalate() {
        val up = listOf(line("GREETING", "Hi.", "PLAIN"), line("QUESTION", "Is anyone hurt?", "CARE", "injury"), line("SAFETY_CHECK", "Are you somewhere safe right now?", "URGENT"))
        assertEquals(emptyList<String>(), DialogueInvariants.check(up, listOf("injury")))
        val down = listOf(line("QUESTION", "Is anyone hurt?", "CARE", "injury"), line("QUESTION", "Where is it?", "PLAIN", "location"))
        assertTrue(DialogueInvariants.check(down, listOf("injury", "location")).any { it == "tone mode decreased during the dialogue" })
    }
}

class EvalMetricsTest {
    private val eps = 1e-9

    private fun field(f: String, match: MatchLevel, placement: Placement) = FieldResult(f, "exp", "act", match.name, placement.name)

    // Case A: everything as expected except one wrong field.
    private val a = CaseResult(
        id = "A", status = "ok", input = "audio",
        utterances = listOf(
            UtteranceResult(key = null, reference = "a b c d e", hypothesis = "a b c d x", asrMs = 300, wer = WerResult(1, 0, 0, 5)),
        ),
        result = "review", expectedResult = "review", mode = "CARE", expectedMode = "CARE",
        asked = listOf("injury", "location"), expectedAsked = listOf("injury", "location"),
        fields = listOf(
            field("incident_location", MatchLevel.EXACT, Placement.CORRECT),
            field("date_time", MatchLevel.RELAXED, Placement.CORRECT),
            field("incident_type", MatchLevel.NONE, Placement.WRONG),
        ),
        emptyChecks = listOf(EmptyCheck("injury_type", null), EmptyCheck("incident_description", "made up")),
        outcomeChecks = listOf(OutcomeCheck("injury", "filled", "filled")),
        turns = listOf(
            TurnResult("injury", outcome = "filled", validator = "pass", rephraseMs = 800),
            TurnResult("location", outcome = "no_response", validator = "fail:too_long:25", rephraseMs = 1600),
        ),
        llmCalls = listOf(LlmCallResult("extract", ms = 1000, prefillMs = 400, decodeMs = 600)),
        firstFormMs = 1300,
    )

    // Case B: wrong mode, wrong asked fields, one ask-once violation and one invariant violation.
    private val b = CaseResult(
        id = "B", status = "ok", input = "audio",
        utterances = listOf(UtteranceResult(key = null, asrMs = 500, wer = WerResult(0, 1, 1, 15))),
        result = "review", expectedResult = "review", mode = "PLAIN", expectedMode = "CARE",
        asked = listOf("injury"), expectedAsked = listOf("location"),
        fields = listOf(field("injury", MatchLevel.NORMALIZED, Placement.CORRECT), field("incident_location", MatchLevel.NONE, Placement.MISPLACED)),
        outcomeChecks = listOf(OutcomeCheck("injury", "no_response", "unverified")),
        turns = listOf(TurnResult("injury", outcome = "unverified", validator = "timeout", rephraseMs = 1500)),
        askOnceViolations = 1, invariantViolations = listOf("x"),
        llmCalls = listOf(
            LlmCallResult("extract", ms = 2000, prefillMs = 1000, decodeMs = 1000),
            LlmCallResult("update", ms = 5, prefillMs = null, decodeMs = null),
        ),
        firstFormMs = 2500,
    )

    private val skipped = CaseResult(id = "C", status = "skipped", detail = "audio missing", input = "audio")
    private val failed = CaseResult(id = "D", status = "error", detail = "boom", input = "audio", fields = listOf(field("x", MatchLevel.EXACT, Placement.CORRECT)))

    private val summary = EvalMetrics.summarize(listOf(a, b, skipped, failed), asrAvailable = true, asrNote = "whisper-small")

    @Test fun caseCounts() {
        assertEquals(4, summary.casesTotal)
        assertEquals(2, summary.casesOk)
        assertEquals(1, summary.casesError)
        assertEquals(1, summary.casesSkipped)
    }

    @Test fun asrWerMeanAndCorpus() {
        val asr = summary.asr
        assertTrue(asr.available)
        assertEquals(2, asr.cases)
        assertEquals(2, asr.utterances)
        // A: 1/5 = 0.2, B: 2/15; mean = (0.2 + 0.13333..) / 2, corpus = 3 errors / 20 words
        assertEquals((0.2 + 2.0 / 15) / 2, asr.meanWer!!, eps)
        assertEquals(0.15, asr.corpusWer!!, eps)
    }

    @Test fun asrIsNotAvailableWithoutRealRecognition() {
        val s = EvalMetrics.summarize(listOf(a), asrAvailable = false, asrNote = "x")
        assertFalse(s.asr.available)
        assertEquals("n/a (no ASR)", s.asr.note)
        assertNull(s.asr.meanWer)
        assertNull(s.asr.corpusWer)
    }

    @Test fun extractionAccuracyPerFieldAndOverall() {
        val e = summary.extraction
        assertEquals(5, e.total) // 3 from A, 2 from B; the failed case does not count
        assertEquals(3, e.matched) // A: exact, relaxed; B: normalised
        assertEquals(1, e.exact)
        assertEquals(1, e.normalized)
        assertEquals(1, e.relaxed)
        assertEquals(0.6, e.accuracy!!, eps)
        assertEquals(2, e.perField.getValue("incident_location").total)
        assertEquals(1, e.perField.getValue("incident_location").matched)
        assertEquals(0.5, e.perField.getValue("incident_location").accuracy!!, eps)
        assertEquals(0.0, e.perField.getValue("incident_type").accuracy!!, eps)
        assertEquals(listOf("date_time", "incident_location", "incident_type", "injury"), e.perField.keys.toList()) // sorted
        assertEquals(2, e.emptyChecks)
        assertEquals(1, e.emptyCorrect)
    }

    @Test fun mappingPlacementRate() {
        val m = summary.mapping
        assertEquals(3, m.correct)
        assertEquals(1, m.misplaced)
        assertEquals(1, m.wrong)
        assertEquals(0, m.missing)
        assertEquals(0.75, m.placementRate!!, eps) // 3 / (3 + 1)
    }

    @Test fun dialogueMetrics() {
        val d = summary.dialogue
        assertEquals(2, d.reports)
        assertEquals(3, d.questionsTotal)
        assertEquals(1.5, d.questionsPerReport!!, eps)
        assertEquals(2, d.questionsMax)
        assertEquals(2, d.modeTotal)
        assertEquals(1, d.modeCorrect)
        assertEquals(0.5, d.modeAccuracy!!, eps)
        assertEquals(2, d.askedTotal)
        assertEquals(1, d.askedOk) // A matches its list; B asked [injury] instead of [location]
        assertEquals(2, d.outcomeTotal)
        assertEquals(1, d.outcomeCorrect)
        assertEquals(2, d.flowTotal)
        assertEquals(2, d.flowOk)
        assertEquals(1, d.askOnceViolations)
        assertEquals(1, d.invariantViolations)
        assertEquals(mapOf("injury" to mapOf("filled" to 1, "unverified" to 1), "location" to mapOf("no_response" to 1)), d.outcomeMix)
    }

    @Test fun askedFieldsExpectationVariants() {
        fun c(asked: List<String>, exact: List<String>?, inc: List<String> = emptyList(), exc: List<String> = emptyList()) =
            CaseResult(id = "X", status = "ok", input = "transcript", asked = asked, expectedAsked = exact, askedIncludes = inc, askedExcludes = exc)
        assertNull(c(listOf("injury"), null).askedOk)
        assertEquals(true, c(listOf("injury", "location"), listOf("injury", "location")).askedOk)
        assertEquals(false, c(listOf("location", "injury"), listOf("injury", "location")).askedOk) // order matters
        assertEquals(true, c(listOf("injury_type"), null, inc = listOf("injury_type"), exc = listOf("injury", "location")).askedOk)
        assertEquals(false, c(listOf("injury", "injury_type"), null, inc = listOf("injury_type"), exc = listOf("injury")).askedOk)
        assertEquals(false, c(emptyList(), null, inc = listOf("injury")).askedOk)
    }

    @Test fun flowResultMismatchIsCounted() {
        val silent = CaseResult(id = "S", status = "ok", input = "transcript", result = "review", expectedResult = "no_narration")
        assertFalse(silent.resultOk)
        assertEquals(0, EvalMetrics.dialogue(listOf(silent)).flowOk)
    }

    @Test fun rephraserMetrics() {
        val r = summary.rephraser
        assertEquals(3, r.attempts)
        assertEquals(1, r.passed)
        assertEquals(1.0 / 3, r.passRate!!, eps)
        assertEquals(mapOf("timeout" to 1, "too_long" to 1), r.rejectionReasons)
        val l = r.latency!!
        assertEquals(3, l.count)
        assertEquals(1500.0, l.median, eps)
        assertEquals(1600.0, l.p90, eps) // rank ceil(2.7) = 3
    }

    @Test fun rephraserMetricsWithoutAttempts() {
        val r = EvalMetrics.rephraser(listOf(CaseResult(id = "X", status = "ok", input = "transcript", turns = listOf(TurnResult("injury", outcome = "filled")))))
        assertEquals(0, r.attempts)
        assertNull(r.passRate)
        assertNull(r.latency)
    }

    @Test fun latencyStats() {
        val l = summary.latency
        assertEquals(2, l.asr!!.count)
        assertEquals(400.0, l.asr!!.median, eps) // 300 and 500
        assertEquals(500.0, l.asr!!.p90, eps)
        assertEquals(2, l.prefill!!.count) // the update call without new metrics has none
        assertEquals(700.0, l.prefill!!.median, eps) // 400 and 1000
        assertEquals(800.0, l.decode!!.median, eps) // 600 and 1000
        assertEquals(1900.0, l.total!!.median, eps) // 1300 and 2500
        assertEquals(2500.0, l.total!!.p90, eps)
    }

    @Test fun emptyRunHasNoNumbers() {
        val s = EvalMetrics.summarize(emptyList(), asrAvailable = false, asrNote = "")
        assertEquals(0, s.casesTotal)
        assertNull(s.extraction.accuracy)
        assertNull(s.mapping.placementRate)
        assertNull(s.dialogue.questionsPerReport)
        assertNull(s.dialogue.modeAccuracy)
        assertNull(s.latency.total)
        assertEquals(0, s.dialogue.askOnceViolations)
    }
}
