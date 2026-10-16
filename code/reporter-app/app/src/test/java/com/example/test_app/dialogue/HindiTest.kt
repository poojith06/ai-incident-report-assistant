package com.example.test_app.dialogue

import com.example.test_app.ai.FakeEngine
import com.example.test_app.ai.LlmIncidentExtractionService
import com.example.test_app.ai.Prompts
import com.example.test_app.domain.model.FieldStatus
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.asStringList
import com.example.test_app.domain.model.asTextOrNull
import com.example.test_app.domain.service.SeverityPolicy
import com.example.test_app.domain.service.SeverityInput
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Non-English narration and answers (Devanagari used as the main example; the script logic is generic, ADR-051): the report stays
 * English, the cues and parsers read the cue lists, the language is the STT-reported name or else the dominant Unicode script.
 */
class HindiTest {
    private val assets = TestAssets.assets
    private val cues = AnswerCues(assets.fieldKeywords)
    private val classifier = ModeClassifier(assets.modeKeywords)

    private val narration = "के आर बी के वॉशरूम में पानी लीक हो रहा है और यह थर्ड फ्लोर में हो रहा है"

    // ---- LanguageDetect ----

    @Test fun languageFallbackIsTheDominantUnicodeScript() {
        assertEquals("devanagari", LanguageDetect.detect(narration))
        assertEquals("telugu", LanguageDetect.detect("నీళ్ళు లీక్ అవుతున్నాయి"))
        assertEquals("tamil", LanguageDetect.detect("கேஆர்பி டேங்க் ஓவர்ஃப்ளோ ஆகுது"))
        assertEquals("oriya", LanguageDetect.detect("ଏଠାରେ ପାଣି ଲିକ୍ ହେଉଛି"))
        assertEquals("bengali", LanguageDetect.detect("জল পড়ছে এখানে")) // Bengali script (also used for Assamese)
        assertEquals("bengali", LanguageDetect.detect("পানী পৰি আছে")) // Assamese letters are in the Bengali block too
        assertEquals("en", LanguageDetect.detect("There is a fire in the lab"))
        assertEquals("en", LanguageDetect.detect("bhai aag lag gayi hai lab mein"))
        assertEquals("en", LanguageDetect.detect("annaya neellu leak avtunnayi")) // romanized: Latin
        assertEquals("en", LanguageDetect.detect(""))
        assertEquals("en", LanguageDetect.detect("1234 !!"))
        assertEquals("devanagari", LanguageDetect.detect("fire in KRB, पानी लीक हो रहा है यहाँ पर"))
        assertEquals("tamil", LanguageDetect.detect("केआरबी ना டங்க் ரொம்ப ஓவர்ஃப்ளோ ஆகுது இங்கே")) // mixed scripts: the larger one wins
        assertEquals("en", LanguageDetect.detect("The tank near the KRB building is overflowing, உ"))
    }

    @Test fun theSttsOwnLanguageNameWinsOverTheScript() {
        assertEquals("tamil", LanguageDetect.resolve("Tamil", narration))
        assertEquals("assamese", LanguageDetect.resolve("assamese", "পানী পৰি আছে"))
        assertEquals("en", LanguageDetect.resolve("English", "bhai aag lag gayi"))
        assertEquals("hindi", LanguageDetect.resolve("Hindi (Devanagari)", narration))
        assertEquals("script fallback when the STT said nothing", "devanagari", LanguageDetect.resolve("", narration))
        assertEquals("devanagari", LanguageDetect.resolve(null, narration))
        assertEquals("unknown is no answer", "devanagari", LanguageDetect.resolve("unknown", narration))
        assertEquals("en", LanguageDetect.resolve("", "water leak"))
    }

    @Test fun nonLatinScriptTestIsGenericNotDevanagariOrTelugu() {
        for (t in listOf("के आर बी", "நீர் கசிவு", "ପାଣି", "জল", "నీళ్ళు", "ನೀರು", "പാനി", "ਪਾਣੀ", "પાણી", "ماء", "水漏れ")) assertTrue(t, LanguageDetect.hasNonLatin(t))
        for (t in listOf("KRB washroom 3rd floor", "café", "naïve", "Ångström lab", "ǆ", "ḥ", "1234", "!!! ??")) assertFalse(t, LanguageDetect.hasNonLatin(t))
    }

    @Test fun nonLatinAndSpeakableText() {
        assertTrue(LanguageDetect.hasNonLatin("के आर बी"))
        assertFalse(LanguageDetect.hasNonLatin("KRB washroom, 3rd floor"))
        assertFalse(LanguageDetect.hasNonLatin("café"))
        assertEquals("the place you mentioned", LanguageDetect.speakable("के आर बी के वॉशरूम", "the place you mentioned"))
        assertEquals("KRB washroom", LanguageDetect.speakable("KRB washroom", "x"))
    }

    // ---- answers ----

    @Test fun hindiUnsureAndSkip() {
        for (a in listOf("पता नहीं", "मालूम नहीं", "नहीं पता", "छोड़ो", "रहने दो", "pata nahi", "नई टाइम मत बदलो रहने दो")) assertTrue(a, cues.isUnsure(a))
        assertFalse(cues.isUnsure("नहीं"))
        assertFalse(cues.isUnsure("हाँ"))
    }

    @Test fun hindiNoChangeAndHere() {
        assertTrue(cues.isNoChange("ठीक है"))
        assertTrue(cues.isNoChange("नहीं।"))
        assertTrue(cues.isHere("यहीं"))
    }

    // ---- mode cues ----

    private fun rule(vararg t: String) = classifier.ruleMode(t.toList())

    @Test fun hindiUrgentCues() {
        for (t in listOf("लैब में आग लग गई है", "बहुत धुआं निकल रहा है", "गैस की बदबू आ रही है", "बिजली का झटका लगा", "वो बेहोश है", "खून बहुत बह रहा है", "मदद चाहिए", "जल्दी आइए", "aag lag gayi", "dhuan nikal raha hai")) {
            assertEquals(t, DialogueMode.URGENT, rule(t))
        }
    }

    @Test fun hindiCareCues() {
        for (t in listOf("उसे चोट लगी है", "एक आदमी घायल हो गया", "वो गिर गया", "बहुत दर्द है", "chot lagi hai")) {
            assertEquals(t, DialogueMode.CARE, rule(t))
        }
    }

    @Test fun hindiNegationGuardCoversBeforeAndAfter() {
        assertEquals(DialogueMode.PLAIN, rule("कोई चोट नहीं है"))
        assertEquals(DialogueMode.PLAIN, rule("चोट नहीं लगी"))
        assertEquals(DialogueMode.PLAIN, rule("बिना चोट के"))
        assertEquals(DialogueMode.PLAIN, rule("koi chot nahi"))
        assertEquals(DialogueMode.PLAIN, rule("chot nahi lagi"))
        assertEquals(DialogueMode.PLAIN, rule("मदद नहीं चाहिए".replace("मदद नहीं चाहिए", "कोई मदद चाहिए नहीं")))
        assertEquals(DialogueMode.CARE, rule("उसे चोट लगी है, नहीं रुकेगा खून")) // another clause: the negation is not about चोट
    }

    @Test fun hindiNarrationAboutAWaterLeakIsPlain() = assertEquals(DialogueMode.PLAIN, rule(narration))

    @Test fun hindiEscalationCuesGiveCriticalInUrgentMode() {
        val s = SeverityPolicy.decide(SeverityInput("Medium", true, listOf("आग लग गई है, मदद चाहिए"), null, null, false, "Fire"), assets.severityCues)
        assertEquals("Critical", s)
        val notNeeded = SeverityPolicy.decide(SeverityInput("Medium", true, listOf("जल्दी नहीं है"), null, null, false, null), assets.severityCues)
        assertEquals("High", notNeeded)
    }

    // ---- the report stays English ----

    private class Run(narration: String, extractionReply: String, answers: List<AsrResult?> = emptyList(), updateReply: (String) -> String = { "}" }) {
        val engine = FakeEngine(replyFor = { prompt ->
            when {
                prompt.endsWith(Prompts.EXTRACTION_PREFIX) -> extractionReply
                prompt.endsWith(Prompts.UPDATE_PREFIX) -> updateReply(prompt)
                else -> updateReply(prompt) // the translation call
            }
        })
        val out = RecordingOut()
        val logger = DialogueLogger()
        val manager = DialogueManager(
            TestAssets.assets, ScriptIn(listOf(said(narration)) + answers), out, LlmIncidentExtractionService(engine) { TEST_CLOCK },
            DialogueConfig(useLlmRephrase = false), logger = logger, clock = { TEST_CLOCK },
        )

        suspend fun review(): IncidentReport = (manager.run() as DialogueOutcome.Review).report
        val spoken get() = manager.spoken.map { it.text }
    }

    private fun IncidentReport.text(key: String) = fields[key]?.value?.asTextOrNull()

    // the reported bug: the model left Devanagari in location
    private val devanagariLocation = """ "Water Leak", "severity": "Medium", "location": "के आर बी के वॉशरूम, थर्ड फ्लोर", "datetime": null, "persons_involved": null, "actions_taken": null, "description": "Water is leaking in the KRB washroom on the third floor."}"""
    private val englishLocation = """ "Water Leak", "severity": "Medium", "location": "KRB washroom, third floor", "datetime": null, "persons_involved": null, "actions_taken": null, "description": "Water is leaking in the KRB washroom on the third floor."}"""

    @Test fun inputLanguageIsTheScriptWhenTheSttGaveNoneAndTheTranscriptIsUntouched() = runTest {
        val r = Run(narration, englishLocation)
        val report = r.review()
        assertEquals("devanagari", report.input.language)
        assertEquals(narration, report.input.transcript)
        assertEquals("KRB washroom, third floor", report.text("incident_location"))
        assertEquals("Water is leaking in the KRB washroom on the third floor.", report.text("incident_description"))
        assertTrue(r.logger.lines.any { it.contains("\"lang\":\"devanagari\"") })
    }

    @Test fun theSttReportedLanguageIsStoredAsReportedNotTheScript() = runTest {
        val tamil = "கேஆர்பி டேங்க் ஓவர்ஃப்ளோ ஆகுது"
        val engine = FakeEngine(replyFor = { englishLocation })
        val manager = DialogueManager(
            TestAssets.assets, ScriptIn(listOf(said(tamil, language = "tamil"))), RecordingOut(), LlmIncidentExtractionService(engine) { TEST_CLOCK },
            DialogueConfig(useLlmRephrase = false), clock = { TEST_CLOCK },
        )
        val report = (manager.run() as DialogueOutcome.Review).report
        assertEquals("tamil", report.input.language)
        assertEquals(tamil, report.input.transcript)
        // the same Devanagari text reported as assamese stays assamese
        val engine2 = FakeEngine(replyFor = { englishLocation })
        val m2 = DialogueManager(
            TestAssets.assets, ScriptIn(listOf(said(narration, language = "assamese"))), RecordingOut(), LlmIncidentExtractionService(engine2) { TEST_CLOCK },
            DialogueConfig(useLlmRephrase = false), clock = { TEST_CLOCK },
        )
        assertEquals("assamese", (m2.run() as DialogueOutcome.Review).report.input.language)
    }

    @Test fun englishNarrationStaysEnglish() = runTest {
        assertEquals("en", Run("There is a water leak in the KRB washroom on the third floor.", englishLocation).review().input.language)
    }

    @Test fun rawScriptIsNeverStoredInAStructuredField() = runTest {
        val r = Run(narration, devanagariLocation)
        val report = r.review()
        assertNull(report.text("incident_location"))
        val loc = report.fields.getValue("incident_location")
        assertEquals(FieldStatus.UNVERIFIED, loc.effectiveStatus)
        assertEquals("के आर बी के वॉशरूम, थर्ड फ्लोर", loc.rawAnswer)
        assertEquals("the English description from the model is kept", "Water is leaking in the KRB washroom on the third floor.", report.text("incident_description"))
        // the planner asks for the location once (it is empty), in English
        assertTrue("location" in r.manager.askedFields)
        // the session language is Hindi: the lines are the Hindi pack's, and the voice is told so
        for (line in r.spoken) assertTrue("not Hindi: $line", LanguageDetect.detect(line) == "devanagari")
        assertTrue(r.manager.spoken.any { it.kind == SpokenKind.QUESTION && it.field == "location" && LanguageDetect.hasNonLatin(it.text) })
    }

    @Test fun anUnansweredScriptFieldStaysUnverifiedWithItsOriginal() = runTest {
        val report = Run(narration, devanagariLocation, answers = listOf(null, null)).review()
        val loc = report.fields.getValue("incident_location")
        assertEquals(FieldStatus.UNVERIFIED, loc.effectiveStatus)
        assertEquals("के आर बी के वॉशरूम, थर्ड फ्लोर", loc.rawAnswer)
    }

    @Test fun readbackIsSpokenInHindiWithTheEnglishLocationAsAnEnglishRun() = runTest {
        val r = Run(narration, englishLocation)
        r.review()
        val rb = r.manager.spoken.single { it.kind == SpokenKind.READBACK }.text
        assertTrue(rb, LanguageDetect.detect(rb) == "devanagari")
        assertTrue("the incident type comes from the glossary: $rb", rb.contains("पानी का रिसाव"))
        assertTrue("the English location value is kept as it is: $rb", rb.contains("KRB washroom, third floor"))
        assertFalse("no English template words left: $rb", rb.contains("Logged") || rb.contains("Please"))
        assertEquals("every line is handed to the voice with the language of the text", setOf("hi"), r.out.languages.toSet())
    }

    @Test fun formReadoutUsesAGenericPhraseForScript() {
        val report = IncidentReport(
            reportId = "INC-1", status = "x", createdAt = "t", input = com.example.test_app.domain.model.ReportInput("voice", "t"),
            fields = mapOf(
                "incident_type" to com.example.test_app.domain.model.FieldEntry(com.example.test_app.domain.model.textValue("Water Leak"), 0.9),
                "incident_location" to com.example.test_app.domain.model.FieldEntry(com.example.test_app.domain.model.textValue("के आर बी वॉशरूम"), 0.9),
            ),
        )
        val line = FormReadout.review(report)
        assertFalse(line, LanguageDetect.hasNonLatin(line))
        assertTrue(line, line.contains("the place you mentioned"))
    }

    // ---- Hindi answers through the dialogue ----

    private val fireNarration = """ "Accident", "severity": "Medium", "location": "KRB washroom, third floor", "datetime": null, "persons_involved": null, "actions_taken": null, "description": "Water leak."}"""

    // an injured person in the narration (type Injury, nobody named): immediate actions (High) and injured_person are asked
    private val hurtNarration = """ "Injury", "severity": "High", "location": "KRB washroom, third floor", "datetime": null, "persons_involved": null, "actions_taken": null, "description": "Someone slipped in the washroom."}"""

    private fun IncidentReport.persons() = fields.getValue("persons_involved").value.asStringList()

    @Test fun aNonLatinAnswerToInjuredPersonFallsBackToOneTranslationCall() = runTest {
        val r = Run(narration, hurtNarration, answers = listOf(null, said("मेरा दोस्त रवि")), updateReply = { "My friend Ravi" })
        val report = r.review()
        assertEquals(listOf("immediate_actions", "injured_person"), r.manager.askedFields)
        assertEquals("one extraction call and exactly one translation call", 2, r.engine.prompts.size)
        assertTrue(r.engine.prompts.last().contains("Translate the user's message (any Indian language, possibly mixed with English) into one short plain English sentence."))
        assertEquals(listOf("My friend Ravi (injured)"), report.persons())
        assertEquals("the real words stay in the history", "मेरा दोस्त रवि", report.clarification.history.last().answer)
    }

    @Test fun theFallbackAppliesToAnyScriptNotOnlyHindi() = runTest {
        for ((script, answer) in listOf("tamil" to "என் நண்பர் ரவி", "odia" to "ମୋ ବନ୍ଧୁ ରବି", "bengali" to "আমার বন্ধু রবি", "telugu" to "నా స్నేహితుడు రవి")) {
            val r = Run(narration, hurtNarration, answers = listOf(null, said(answer, language = script)), updateReply = { "My friend Ravi" })
            val report = r.review()
            assertEquals(script, 2, r.engine.prompts.size)
            assertEquals(script, listOf("My friend Ravi (injured)"), report.persons())
        }
    }

    @Test fun aLatinAnswerNeedsNoTranslationCall() = runTest {
        val r = Run(narration, hurtNarration, answers = listOf(null, said("my friend Ravi")), updateReply = { "must not be used" })
        r.review()
        assertEquals(1, r.engine.prompts.size)
    }

    @Test fun anUnreadableTranslationIsNeverStoredAsScript() = runTest {
        val r = Run(narration, hurtNarration, answers = listOf(null, said("मेरा दोस्त रवि")), updateReply = { "यह अनुवाद नहीं है" })
        val report = r.review()
        assertTrue(report.persons().toString(), report.persons().none { LanguageDetect.hasNonLatin(it) })
    }

    @Test fun hindiLocationAnswerUsesTheNormalUpdatePathWithEnglishOutput() = runTest {
        val noLoc = """ "Water Leak", "severity": "Medium", "location": null, "datetime": null, "persons_involved": null, "actions_taken": null, "description": "Water leak in a washroom, third floor."}"""
        val r = Run(
            "वॉशरूम में पानी लीक हो रहा है", noLoc, answers = listOf(said("के आर बी के वॉशरूम, थर्ड फ्लोर")),
            updateReply = { """ "KRB washroom, third floor"}""".let { "{\"location\": \"KRB washroom, third floor\"}".removePrefix("{") } },
        )
        val report = r.review()
        assertEquals("KRB washroom, third floor", report.text("incident_location"))
        assertEquals(FieldStatus.FILLED, report.fields.getValue("incident_location").effectiveStatus)
        assertEquals("devanagari", report.input.language)
        assertTrue(r.engine.prompts.last().contains("USER: के आर बी के वॉशरूम, थर्ड फ्लोर"))
    }

    @Test fun aScriptUpdateNeverOverwritesAGoodEnglishValue() = runTest {
        val r = Run(
            narration, englishLocation, answers = listOf(null, null),
            updateReply = { "\"location\": \"वॉशरूम\"}" },
        )
        assertEquals("KRB washroom, third floor", r.review().text("incident_location"))
    }

    @Test fun timeChangeInHindiIsIgnoredNotInvented() = runTest {
        val r = Run(narration, englishLocation, answers = listOf(null), updateReply = { "}" })
        val report = r.review()
        assertEquals("default_now", report.timeSource)
    }

    @Test fun bankAndAssetsStayValidWithTheHindiEntries() {
        assertEquals("hi", assets.questionBank.language.takeIf { false } ?: "hi")
        assertTrue("आग" in assets.modeKeywords.urgent)
        assertTrue("चोट" in assets.modeKeywords.careInjuryRelated)
        assertTrue("नहीं" in assets.modeKeywords.negationWords)
        assertTrue("नहीं" in assets.modeKeywords.negationAfterWords)
        val text = java.io.File(TestAssets.dir, "mode_keywords.json").readText()
        assertTrue(Regex("\"hi\": \\{\\s*\"needs_native_review\": true").containsMatchIn(text))
    }
}
