package com.example.test_app.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Checks the shipped `app/src/main/assets/eval/cases.json` (canonical) and the parser. */
class EvalCasesTest {
    private val file: File = listOf("src/main/assets/eval/cases.json", "app/src/main/assets/eval/cases.json").map(::File).first { it.isFile }
    private val cases: List<EvalCase> by lazy { EvalCases.parse(file.readText()) }

    @Test fun hasAtLeastTwentyUniqueCases() {
        assertTrue("cases: ${cases.size}", cases.size >= 20)
        assertEquals(cases.size, cases.map { it.id }.toSet().size)
    }

    @Test fun coversEverySpecV2ScenarioAndTheIntegrationGuideRows() {
        val tags = cases.flatMap { it.tags }.toSet()
        for (n in 1..13) assertTrue("spec-$n has no case", "spec-$n" in tags)
        // spec 14 (invariants) is checked on every case by the runner; guide row 7 (airplane mode) is a manual run of the whole set
        for (n in 1..6) assertTrue("guide-$n has no case", "guide-$n" in tags)
        for (t in listOf("short", "long", "noise", "fast", "place-names", "ask-once", "negation")) assertTrue("no case tagged $t", t in tags)
    }

    @Test fun usesThePlaceNamesWhisperMightMishear() {
        val text = cases.joinToString(" ") { it.transcript.orEmpty() }.lowercase()
        for (p in listOf("block b", "conference room", "server room", "pantry", "canteen", "lab 3", "lab three")) {
            assertTrue("no transcript mentions \"$p\"", p in text)
        }
    }

    @Test fun eachScenarioStatesItsExpectation() {
        val byId = cases.associateBy { it.id }
        val e01 = byId.getValue("E01")
        assertEquals("There was a water leak near Conference Room 2 around 10 AM. Maintenance has already isolated the area.", e01.transcript)
        assertEquals("PLAIN", e01.expected.mode)
        assertEquals(emptyList<String>(), e01.expected.askedFields)
        assertEquals("Water Leak", e01.expected.fields["incident_type"])
        assertEquals("10 AM", e01.expected.fields["date_time"])
        assertEquals("no_narration", byId.getValue("E11").expected.result)
        assertTrue(byId.getValue("E11").silent)
        assertEquals("URGENT", byId.getValue("E05").expected.mode)
        assertEquals(true, byId.getValue("E05").expected.emergency)
        assertEquals(0.3f, byId.getValue("E09").answerFor("location")!!.asrConf)
        assertEquals("facility issue: nothing is asked (ADR-048)", emptyList<String>(), byId.getValue("E08").expected.askedFields)
        assertEquals(listOf("location", "description"), byId.getValue("E02").expected.askedFields)
        assertEquals(emptyList<String>(), byId.getValue("E12").followUpAnswers.map { it.field })
        assertEquals(listOf("description", "time"), byId.getValue("E07").expected.askedFields)
        assertEquals("unknown_reporter_unsure", byId.getValue("E07").expected.outcomes["time"])
    }

    @Test fun audioFilesAreWellNamedAndUnique() {
        val audio = cases.flatMap { c -> listOfNotNull(c.audio) + c.followUpAnswers.mapNotNull { it.audio } }
        assertEquals("audio paths must be unique", audio.size, audio.toSet().size)
        // Human recordings (tag human-recording) keep the file names of samples/ (audio/test-N.wav); every other file is audio/E<nn>_<name>.wav.
        val human = cases.filter { "human-recording" in it.tags }.mapNotNull { it.audio }.toSet()
        for (a in audio) {
            val pattern = if (a in human) "audio/test-\\d+\\.wav" else "audio/E\\d\\d_[a-z0-9_]+\\.wav"
            assertTrue("bad audio path $a", Regex(pattern).matches(a))
        }
        for (c in cases) {
            c.audio?.takeIf { it !in human }?.let { assertTrue("${c.id}: audio $it does not start with the case id", it.startsWith("audio/${c.id}_")) }
            for (a in c.followUpAnswers) {
                assertTrue("${c.id}: a silent answer has no audio", a.transcript != null || a.audio == null)
                a.audio?.let { assertTrue("${c.id}: $it", it.startsWith("audio/${c.id}_a")) }
            }
            if (!c.silent && !c.photoOnly) assertTrue("${c.id} needs an audio path", c.audio != null)
            assertTrue("${c.id} audio path needs a transcript (the WER reference)", c.audio == null || !c.transcript.isNullOrBlank())
        }
    }

    @Test fun audioDirectoryExistsWithOnlyAGitkeep() {
        val dir = listOf("../eval/audio", "eval/audio").map(::File).firstOrNull { it.isDirectory }
        assertTrue("eval/audio is missing", dir != null)
        assertTrue(File(dir!!, ".gitkeep").isFile)
        assertEquals(emptyList<String>(), dir.listFiles().orEmpty().map { it.name }.filter { it != ".gitkeep" && !it.endsWith(".wav") })
    }

    @Test fun requiredAudioListsTheNarrationAndEveryNonSilentAnswer() {
        val e08 = cases.first { it.id == "E08" }
        assertEquals(listOf("audio/E08_ac_block_c.wav"), e08.requiredAudio())
        val e02 = cases.first { it.id == "E02" }
        assertEquals(listOf("audio/E02_water_on_floor.wav", "audio/E02_a2_location.wav"), e02.requiredAudio())
        assertEquals(emptyList<String>(), cases.first { it.id == "E11" }.requiredAudio())
    }

    // ---- parser ----------------------------------------------------------------------------------

    private fun bad(json: String, fragment: String) {
        try {
            EvalCases.parse(json)
            fail("expected an EvalCaseException containing \"$fragment\"")
        } catch (e: EvalCaseException) {
            assertTrue("\"${e.message}\" should contain \"$fragment\"", e.message!!.contains(fragment))
        }
    }

    private fun one(extra: String = "", expected: String = """"mode": "PLAIN", "fields": {}""") =
        """[{"id": "T1", "transcript": "hello there", $extra "expected": {$expected}}]"""

    @Test fun parsesAllOptionalParts() {
        val c = EvalCases.parse(
            """[{"id": "T1", "audio": "audio/T1_x.wav", "transcript": "hello", "tags": ["a"], "notes": "n",
                "follow_up_answers": [{"field": "location", "transcript": "no", "audio": "audio/T1_a1.wav", "asr_conf": 0.4}, {"field": "safety", "transcript": null}],
                "expected": {"mode": "CARE", "fields": {"injury": true, "persons_involved": ["Ravi", "Priya"], "incident_type": "Fire"},
                  "empty_fields": ["injury_type"], "asked_fields": ["location"], "asked_includes": ["description"], "asked_excludes": ["time"],
                  "outcomes": {"location": "filled"}, "emergency": true, "result": "review"}}]""",
        ).single()
        assertEquals("T1", c.id)
        assertEquals(listOf("a"), c.tags)
        assertEquals(2, c.followUpAnswers.size)
        assertEquals(0.4f, c.answerFor("location")!!.asrConf)
        assertEquals(null, c.answerFor("safety")!!.transcript)
        assertEquals(mapOf("injury" to "true", "persons_involved" to "Ravi; Priya", "incident_type" to "Fire"), c.expected.fields)
        assertEquals(listOf("injury_type"), c.expected.emptyFields)
        assertEquals(listOf("location"), c.expected.askedFields)
        assertEquals(listOf("description"), c.expected.askedIncludes)
        assertEquals(listOf("time"), c.expected.askedExcludes)
        assertEquals(true, c.expected.emergency)
    }

    @Test fun acceptsAnObjectWithACasesArray() {
        assertEquals(1, EvalCases.parse("""{"_comment": "x", "cases": ${one()}}""").size)
    }

    @Test fun rejectsMalformedCases() {
        bad("not json", "not valid JSON")
        bad("""{"a": 1}""", "JSON array")
        bad("""[{"transcript": "x", "expected": {}}]""", "no \"id\"")
        bad("""[{"id": "T1", "expected": {}}]""", "\"transcript\" is required")
        bad("""[{"id": "T1", "transcript": "x"}]""", "missing \"expected\"")
        bad(one() + "x", "not valid JSON")
        bad(one(expected = "\"mode\": \"LOUD\""), "expected.mode")
        bad(one(expected = "\"fields\": {\"nope\": \"x\"}"), "unknown report field")
        bad(one(expected = "\"empty_fields\": [\"nope\"]"), "unknown report field")
        bad(one(expected = "\"asked_fields\": [\"incident_type\"]"), "unknown dialogue field")
        bad(one(expected = "\"outcomes\": {\"location\": \"maybe\"}"), "expected.outcomes.location")
        bad(one(expected = "\"result\": \"later\""), "expected.result")
        bad(one(extra = """"follow_up_answers": [{"field": "colour", "transcript": "x"}],"""), "unknown answer field")
        bad(one(extra = """"follow_up_answers": [{"transcript": "x"}],"""), "no \"field\"")
        bad(
            one(extra = """"follow_up_answers": [{"field": "location", "transcript": "a"}, {"field": "location", "transcript": "b"}],"""),
            "ask-once",
        )
    }

    @Test fun rejectsDuplicateIds() {
        val a = """{"id": "T1", "transcript": "x", "expected": {}}"""
        bad("[$a, $a]", "duplicate case ids")
    }

    @Test fun silentCaseNeedsNoTranscript() {
        val c = EvalCases.parse("""[{"id": "T1", "expected": {"result": "no_narration"}}]""").single()
        assertTrue(c.silent)
        assertEquals("no_narration", c.expected.result)
    }

    // ---- photo-only cases (ADR-062) ----

    @Test fun thereAreSixPhotoOnlyCasesWithPhotosAndNoSpeech() {
        val photo = cases.filter { it.photoOnly }
        assertEquals(listOf("P01", "P02", "P03", "P04", "P05", "P06"), photo.map { it.id })
        for (c in photo) {
            assertEquals(1, c.images.size)
            assertTrue("${c.id}: ${c.images}", Regex("images/[a-z_A-Z0-9]+\\.(jpe?g|png)").matches(c.images.single()))
            assertTrue(c.transcript == null && c.audio == null && c.followUpAnswers.isEmpty())
            assertEquals("nothing may be asked", emptyList<String>(), c.expected.askedFields)
            assertTrue("a floor for the severity", c.expected.severityMin in listOf("Low", "Medium", "High", "Critical"))
            assertTrue(c.expected.fields.containsKey("incident_type"))
            assertTrue("never invent people, injury or actions", c.expected.emptyFields.containsAll(listOf("persons_involved", "injury_type", "immediate_actions_taken")))
            assertTrue(c.silent.not())
            assertTrue("photo-only" in c.tags && "no-follow-up" in c.tags)
        }
        val byId = photo.associateBy { it.id }
        assertEquals("Fire", byId.getValue("P02").expected.fields["incident_type"])
        assertEquals("High", byId.getValue("P02").expected.severityMin)
        assertEquals("Water Leak", byId.getValue("P03").expected.fields["incident_type"])
        assertEquals("Low", byId.getValue("P03").expected.severityMin)
        assertEquals("Electrical Hazard", byId.getValue("P05").expected.fields["incident_type"])
        assertEquals(photo.map { it.images.single() }.size, photo.map { it.images.single() }.toSet().size)
    }

    private fun parseOne(extra: String, expected: String = "\"expected\": {\"fields\": {\"incident_type\": \"Fire\"}}") =
        EvalCases.parse("""[{"id": "X1", $extra, $expected}]""")

    @Test fun aPhotoOnlyCaseNeedsNoTranscriptButAnOrdinaryCaseStillDoes() {
        parseOne("\"images\": [\"images/a.jpg\"]")
        try {
            parseOne("\"tags\": []")
            org.junit.Assert.fail("a case without transcript and images")
        } catch (e: EvalCaseException) { /* expected */ }
    }

    @Test fun aPhotoOnlyCaseHasNoSpeechAndNoAnswersAndAValidSeverityFloor() {
        for (bad in listOf(
            "\"images\": [\"images/a.jpg\"], \"transcript\": \"hello\"",
            "\"images\": [\"images/a.jpg\"], \"audio\": \"audio/X1_a.wav\"",
            "\"images\": [\"images/a.jpg\"], \"follow_up_answers\": [{\"field\": \"location\", \"transcript\": \"here\"}]",
        )) {
            try {
                parseOne(bad)
                org.junit.Assert.fail(bad)
            } catch (e: EvalCaseException) { /* expected */ }
        }
        try {
            parseOne("\"images\": [\"images/a.jpg\"]", "\"expected\": {\"fields\": {}, \"severity_min\": \"Huge\"}")
            org.junit.Assert.fail("bad severity_min")
        } catch (e: EvalCaseException) { /* expected */ }
        assertEquals("High", parseOne("\"images\": [\"images/a.jpg\"]", "\"expected\": {\"fields\": {}, \"severity_min\": \"High\"}").single().expected.severityMin)
    }
}
