package com.example.test_app.dialogue

import com.example.test_app.domain.model.Clarification
import com.example.test_app.domain.model.FieldEntry
import com.example.test_app.domain.model.FieldStatus
import com.example.test_app.domain.model.IncidentField
import com.example.test_app.domain.model.IncidentReport
import com.example.test_app.domain.model.ReportInput
import com.example.test_app.domain.model.Severity
import com.example.test_app.domain.model.TimeSource
import com.example.test_app.domain.model.Validation
import com.example.test_app.domain.model.boolValue
import com.example.test_app.domain.model.textValue
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Language packs: names and codes, the hi / te assets, localized read-outs and a dialogue in the language that was heard. */
class LanguagePackTest {
    private val assets = TestAssets.assets
    private val hi = assets.pack("hindi")
    private val te = assets.pack("telugu")

    private fun t(v: String) = FieldEntry(textValue(v), 0.9)

    private fun report(
        vararg fields: Pair<String, FieldEntry>,
        missing: List<String> = emptyList(),
        timeSource: String? = null,
        id: String = "INC-2026-665948",
    ) = IncidentReport(
        reportId = id, status = "x", createdAt = "2026-10-11T09:30:00+05:30", input = ReportInput("voice", "x"),
        fields = mapOf(*fields), validation = Validation(missingFields = missing), clarification = Clarification(), timeSource = timeSource,
    )

    private fun hasLatin(s: String) = s.any { it in 'A'..'Z' || it in 'a'..'z' }

    // ---- names and codes ----------------------------------------------------------------------------------------------------------------

    @Test fun languageCodesAcceptNamesCodesAndScripts() {
        for (n in listOf("hindi", "Hindi", "HI", "hi", " hindi ", "Hindi (India)", "devanagari")) assertEquals(n, "hi", LanguageNames.code(n))
        assertEquals("te", LanguageNames.code("Telugu"))
        assertEquals("te", LanguageNames.code("telugu"))
        assertEquals("as", LanguageNames.code("assamese"))
        assertEquals("or", LanguageNames.code("Odia"))
        assertEquals("or", LanguageNames.code("oriya"))
        assertEquals("bn", LanguageNames.code("bengali"))
        assertEquals("en", LanguageNames.code("English"))
        assertEquals("en", LanguageNames.code("en"))
    }

    @Test fun unknownOrBlankLanguagesHaveNoCode() {
        for (n in listOf(null, "", "  ", "klingon", "unknown", "n/a")) assertNull(n, LanguageNames.code(n))
    }

    @Test fun textDecidesBetweenScriptsAndTheHintBetweenLanguagesOfAScript() {
        assertEquals("en", LanguageNames.forText("Fire on the third floor", "hindi"))
        assertEquals("te", LanguageNames.forText("మూడవ అంతస్తులో మంటలు", "hindi")) // the hint contradicts the script
        assertEquals("hi", LanguageNames.forText("तीसरी मंजिल पर आग", null))
        assertEquals("mr", LanguageNames.forText("तीसरी मंजिल पर आग", "marathi"))
        assertEquals("as", LanguageNames.forText("তৃতীয় মহলাত জুই", "Assamese"))
        assertEquals("bn", LanguageNames.forText("তৃতীয় মহলাত জুই", ""))
    }

    // ---- the assets ---------------------------------------------------------------------------------------------------------------------

    @Test fun hindiAndTeluguPacksAreLoadedAndOthersFallBackToEnglish() {
        assertEquals("hi", hi.code)
        assertEquals("te", te.code)
        assertEquals("hi", assets.pack("hi").code)
        assertEquals("hi", assets.pack("Devanagari").code)
        assertSame(assets.primaryPack, assets.pack("english"))
        assertSame(assets.primaryPack, assets.pack("en"))
        for (n in listOf("tamil", "kannada", "assamese", "malayalam", "klingon", "", null)) assertSame(n, assets.primaryPack, assets.pack(n))
        assertTrue(assets.primaryPack.isEnglish)
        assertFalse(hi.isEnglish)
    }

    @Test fun theEnglishReadoutObjectIsTheBuiltInDefaultSet() {
        val parsed = ReadoutTemplates.parse(File(TestAssets.dir, "question_bank.en.json").readText())
        assertEquals(ReadoutTemplates.DEFAULTS.keys, parsed.definedKeys)
        for ((k, v) in ReadoutTemplates.DEFAULTS) assertEquals(k, v, parsed[k])
    }

    @Test fun everyPackDefinesAllReadoutKeysWithTheSamePlaceholders() {
        val ph = Regex("\\{(\\w+)}")
        for (pack in listOf(hi, te)) {
            assertEquals(pack.code, ReadoutTemplates.DEFAULTS.keys, pack.readout.definedKeys)
            for ((k, en) in ReadoutTemplates.DEFAULTS) {
                assertEquals("${pack.code}/$k", ph.findAll(en).map { it.value }.toSet(), ph.findAll(pack.readout[k]).map { it.value }.toSet())
            }
        }
    }

    @Test fun everyPackQuestionAndLineIsInItsOwnScript() {
        for ((pack, script) in listOf(hi to "devanagari", te to "telugu")) {
            for ((where, q) in pack.bank.allQuestions()) assertEquals("${pack.code}/$where: $q", script, LanguageDetect.detect(q))
            for (key in QuestionBank.REQUIRED_LINES) assertEquals("${pack.code}/$key", script, LanguageDetect.detect(pack.bank.line(key)))
            assertEquals(script, LanguageDetect.detect(pack.bank.greeting))
        }
    }

    @Test fun glossariesCoverEveryEnumerationTheReadoutsUse() {
        val types = listOf("Water Leak", "Fire", "Injury", "Accident", "Equipment Failure", "Electrical Hazard", "Gas Leak", "Spill", "Security", "Other")
        for (pack in listOf(hi, te)) {
            val g = pack.glossary
            for (ty in types) assertTrue("${pack.code}: incident type $ty", g.incidentType(ty) != null)
            for (s in Severity.ALLOWED) assertTrue("${pack.code}: severity $s", g.severity(s) != null)
            for (f in IncidentField.entries) assertTrue("${pack.code}: field label ${f.key}", g.fieldLabel(f.key) != null)
            for (n in 0..59) assertTrue("${pack.code}: number $n", !g.number(n).isNullOrBlank())
        }
    }

    // ---- templates and glossary mechanics -----------------------------------------------------------------------------------------------

    @Test fun readoutParsingRejectsUnknownKeysAndBlankValuesButKeepsJoinerSpaces() {
        try {
            ReadoutTemplates.parse("""{"readout": {"nonsense": "x"}}""")
            fail("unknown key")
        } catch (e: DialogueAssetException) { /* expected */ }
        try {
            ReadoutTemplates.parse("""{"readout": {"ready": "  "}}""")
            fail("blank value")
        } catch (e: DialogueAssetException) { /* expected */ }
        val parsed = ReadoutTemplates.parse("""{"readout": {"summary_join": " and ", "ready": " Hello. "}}""")
        assertEquals(" and ", parsed["summary_join"])
        assertEquals("Hello.", parsed["ready"])
        assertEquals(ReadoutTemplates.DEFAULTS.getValue("tail"), parsed["tail"])
        assertEquals(ReadoutTemplates.ENGLISH.definedKeys, ReadoutTemplates.parse("""{"fields": {}}""").definedKeys)
    }

    @Test fun glossaryParsingChecksNumbersAndNeedsEntries() {
        try {
            Glossary.parse("""{"incident_type": {"Fire": "F"}, "numbers": {"one": "x"}}""")
            fail("number key")
        } catch (e: DialogueAssetException) { /* expected */ }
        try {
            Glossary.parse("""{"field_labels": {}}""")
            fail("no entries")
        } catch (e: DialogueAssetException) { /* expected */ }
        val g = Glossary.parse("""{"incident_type": {"Water Leak": "WL"}, "severity": {"High": "H"}, "numbers": {"7": "seven", "0": "zero"}}""")
        assertEquals("WL", g.incidentType("water leak"))
        assertEquals("WL", g.incidentType(" Water Leak "))
        assertEquals("H", g.severity("HIGH"))
        assertNull(g.incidentType("Fire"))
        assertEquals("seven", g.number(7))
    }

    @Test fun spellDigitsSpellsEveryDigitAndLeavesTheRest() {
        val g = Glossary.parse("""{"severity": {"Low": "l"}, "numbers": {"0": "zero", "1": "one", "2": "two"}}""")
        assertEquals("I N C, two zero two one, one", g.spellDigits("I N C, 2021, 1"))
        assertEquals("A 3", g.spellDigits("A 3")) // a digit without a word stays
        assertEquals("I N C, 2026", Glossary.NONE.spellDigits("I N C, 2026"))
    }

    // ---- localized read-outs ------------------------------------------------------------------------------------------------------------

    private val complete = report(
        "incident_type" to t("Water Leak"), "incident_location" to t("Conference Room 2"),
        "date_time" to t("2026-10-11T10:00:00+05:30"), "severity_level" to t("Low"), "injury" to FieldEntry(boolValue(false), 0.9),
    )

    @Test fun hindiReviewLine() {
        assertEquals(
            "आपकी रिपोर्ट तैयार है। पानी का रिसाव, Conference Room 2 में, सुबह दस बजे। गंभीरता कम है। किसी को चोट नहीं लगी। कृपया जाँचें और सबमिट दबाएँ।",
            FormReadout.review(complete, hi),
        )
    }

    @Test fun teluguReviewLine() {
        assertEquals(
            "మీ రిపోర్ట్ సిద్ధంగా ఉంది. నీటి లీక్, Conference Room 2 లో, ఉదయం పది గంటలకు. తీవ్రత తక్కువ. ఎవరికీ గాయం కాలేదు. దయచేసి సరిచూసి సబ్మిట్ నొక్కండి.",
            FormReadout.review(complete, te),
        )
    }

    @Test fun englishReviewIsUnchangedWithAnExplicitEnglishPack() {
        val line = "Your report is ready. Water Leak, at Conference Room 2, 10 AM. Severity low. Nobody was hurt. Please review and tap submit."
        assertEquals(line, FormReadout.review(complete))
        assertEquals(line, FormReadout.review(complete, assets.primaryPack))
    }

    @Test fun minutesAndDefaultedTimeAreSpokenInTheLanguage() {
        val afternoon = report("incident_type" to t("Spill"), "date_time" to t("2026-10-11T15:30:00+05:30"), timeSource = TimeSource.SPOKEN)
        assertTrue(FormReadout.review(afternoon, hi), FormReadout.review(afternoon, hi).contains("तरल पदार्थ का फैलना, शाम तीन बजकर तीस मिनट।"))
        assertTrue(FormReadout.review(afternoon, te), FormReadout.review(afternoon, te).contains("ద్రవం ఒలికిపోవడం, సాయంత్రం మూడు గంటల ముప్పై నిమిషాలకు."))
        val now = report("incident_type" to t("Fire"), timeSource = TimeSource.DEFAULT_NOW)
        assertTrue(FormReadout.review(now, hi).contains("आग, अभी-अभी।"))
        assertTrue(FormReadout.review(now, te).contains("అగ్ని ప్రమాదం, ఇప్పుడే."))
    }

    @Test fun severeReportsSpeakTheContactsSentenceInTheLanguage() {
        val fire = report("incident_type" to t("Fire"), "severity_level" to t("Critical"), "injury" to FieldEntry(boolValue(true), 0.9))
        val h = FormReadout.review(fire, hi)
        assertTrue(h, h.contains("किसी को चोट लगी है।") && h.contains("अति गंभीर") && h.contains("आपातकालीन संपर्क नंबर"))
        assertFalse(hasLatin(h))
        val e = FormReadout.review(fire, te)
        assertTrue(e, e.contains("అత్యంత తీవ్రం") && e.contains("అత్యవసర సంప్రదింపు నంబర్లు"))
        assertFalse(hasLatin(e))
    }

    @Test fun missingFieldsAreNamedWithGlossaryLabels() {
        val r = report(
            "incident_type" to t("Fire"), "severity_level" to t("High"),
            "incident_location" to FieldEntry(status = FieldStatus.NO_RESPONSE),
            "incident_description" to FieldEntry(status = FieldStatus.MISSING_FLAGGED),
        )
        val h = FormReadout.review(r, hi)
        assertTrue(h, h.contains("अभी भी बाकी है: घटना का विवरण, स्थान।") && h.contains("चिह्नित जानकारी"))
        val e = FormReadout.review(r, te)
        assertTrue(e, e.contains("ఇంకా కావాల్సినవి: సంఘటన వివరణ, ప్రదేశం.") && e.contains("గుర్తించిన వివరాలను"))
    }

    @Test fun noEnglishLeftoversForEnumeratedFieldsInAnyCombination() {
        val types = listOf("Water Leak", "Fire", "Injury", "Accident", "Equipment Failure", "Electrical Hazard", "Gas Leak", "Spill", "Security", "Other")
        for (pack in listOf(hi, te)) for (ty in types) for (sev in Severity.ALLOWED) for (hurt in listOf(true, false)) {
            for (time in listOf("2026-10-11T00:05:00+05:30", "2026-10-11T09:45:00+05:30", "2026-10-11T12:00:00+05:30", "2026-10-11T23:59:00+05:30")) {
                val r = report(
                    "incident_type" to t(ty), "severity_level" to t(sev), "injury" to FieldEntry(boolValue(hurt), 0.9),
                    "date_time" to t(time),
                    "incident_location" to FieldEntry(status = FieldStatus.NO_RESPONSE), "incident_description" to FieldEntry(status = FieldStatus.UNVERIFIED),
                    "persons_involved" to FieldEntry(status = FieldStatus.MISSING_FLAGGED), "immediate_actions_taken" to FieldEntry(status = FieldStatus.MISSING_FLAGGED),
                    timeSource = TimeSource.SPOKEN,
                )
                val line = FormReadout.review(r, pack)
                assertFalse("${pack.code} $ty $sev $time: $line", hasLatin(line))
            }
        }
    }

    @Test fun onlyTheEnglishLocationValueIsLatinInALocalizedLine() {
        val line = FormReadout.review(complete, hi)
        assertFalse(hasLatin(line.replace("Conference Room 2", "")))
        val long = report("incident_type" to t("Fire"), "incident_location" to t("the second floor server room next to the pantry and the lift lobby"))
        // the free-text value is shortened to six words and kept as an English run
        assertTrue(FormReadout.review(long, hi).contains("the second floor server room next में"))
    }

    @Test fun aGlossaryOrTemplateGapKeepsTheEnglishWordOrSentence() {
        val partial = LanguagePack(
            "xx", assets.questionBank, ReadoutTemplates(mapOf("ready" to "XX READY")), Glossary(incidentTypes = mapOf("fire" to "FEU")),
        )
        assertEquals(
            "XX READY Water Leak. Severity low. Please review and tap submit.",
            FormReadout.review(report("incident_type" to t("Water Leak"), "severity_level" to t("Low")), partial),
        )
        assertEquals("XX READY FEU. Please review and tap submit.", FormReadout.review(report("incident_type" to t("Fire")), partial))
        // a type the glossary does not know stays English in a Hindi line
        val h = FormReadout.review(report("incident_type" to t("Hovercraft Mishap")), hi)
        assertTrue(h, h.contains("Hovercraft Mishap।"))
    }

    @Test fun aNonLatinLocationIsReplacedOnlyForTheEnglishVoice() {
        val r = report("incident_type" to t("Fire"), "incident_location" to t("के आर बी वॉशरूम"))
        assertTrue(FormReadout.review(r).contains("at the place you mentioned"))
        assertTrue(FormReadout.review(r, hi).contains("के आर बी वॉशरूम में"))
    }

    @Test fun submittedLineSpellsTheReferenceInTheLanguage() {
        assertEquals("Report submitted. Reference I N C, 2026, 6 6 5 9 4 8.", FormReadout.submitted(complete))
        assertEquals(
            "रिपोर्ट सबमिट हो गई। संदर्भ संख्या I N C, दो शून्य दो छह, छह छह पाँच नौ चार आठ।",
            FormReadout.submitted(complete, hi),
        )
        assertEquals(
            "రిపోర్ట్ సబ్మిట్ అయింది. రిఫరెన్స్ నంబర్ I N C, రెండు సున్నా రెండు ఆరు, ఆరు ఆరు ఐదు తొమ్మిది నాలుగు ఎనిమిది.",
            FormReadout.submitted(complete, te),
        )
        val severe = report("severity_level" to t("Critical"))
        assertTrue(FormReadout.submitted(severe, hi).endsWith("आप स्क्रीन पर दिए गए आपातकालीन संपर्कों को कॉल कर सकते हैं।"))
        assertTrue(FormReadout.submitted(severe, te).endsWith("స్క్రీన్ మీద ఉన్న అత్యవసర నంబర్లకు మీరు కాల్ చేయవచ్చు."))
        assertFalse(hasLatin(FormReadout.submitted(complete, hi).replace("I N C", "")))
    }

    @Test fun localizedLinesNeverClaimAnythingWasSent() {
        val words = listOf("भेजा", "सूचित", "अलर्ट", "డిస్పాచ్", "పంపబడింది", "పంపాము")
        for (pack in listOf(hi, te)) for (line in listOf(FormReadout.review(complete, pack), FormReadout.submitted(complete, pack))) {
            for (w in words) assertFalse("$w in $line", line.contains(w))
        }
    }

    // ---- the dialogue speaks the language that was heard ---------------------------------------------------------------------------------

    private class Run(
        narration: AsrResult?,
        config: DialogueConfig = DialogueConfig(useLlmRephrase = false),
        rephraser: Rephraser? = null,
        assets: DialogueAssets = TestAssets.assets,
    ) {
        val out = RecordingOut()
        val manager = DialogueManager(
            assets, ScriptIn(listOf(narration ?: said("x"), null, null, null)), out,
            ScriptExtraction(listOf(reportJson("x", "incident_type" to "Water Leak", "severity_level" to "Medium", "incident_description" to "Water leak"))),
            config, rephraser, clock = { TEST_CLOCK },
        )
        suspend fun review() = (manager.run() as DialogueOutcome.Review).report
    }

    @Test fun aHindiSessionAsksAndReadsBackInHindi() = runTest {
        val r = Run(said("पानी लीक हो रहा है", language = "hindi"))
        val report = r.review()
        assertEquals("hindi", report.input.language)
        val loc = r.manager.spoken.first { it.kind == SpokenKind.QUESTION && it.field == "location" }
        assertEquals(hi.bank.question("location", DialogueMode.PLAIN), loc.text)
        assertTrue(r.manager.spoken.isNotEmpty())
        for (line in r.manager.spoken) assertEquals(line.text, "devanagari", LanguageDetect.detect(line.text))
        assertEquals("the voice is told the language of the text", setOf("hi"), r.out.languages.toSet())
        assertEquals(r.manager.spoken.size, r.out.languages.size)
    }

    @Test fun aTeluguSessionUsesTheTeluguPack() = runTest {
        val r = Run(said("నీటి లీక్ అవుతోంది", language = "Telugu"))
        r.review()
        assertEquals(te.bank.question("location", DialogueMode.PLAIN), r.manager.spoken.first { it.field == "location" }.text)
        assertEquals(setOf("te"), r.out.languages.toSet())
        for (line in r.manager.spoken) assertEquals(line.text, "telugu", LanguageDetect.detect(line.text))
    }

    @Test fun aScriptNameFromTheSttSelectsThePackToo() = runTest {
        // no language name from the speech model: the dialogue resolves the script ("devanagari" -> hi)
        val r = Run(said("पानी लीक हो रहा है"))
        r.review()
        assertEquals(setOf("hi"), r.out.languages.toSet())
    }

    @Test fun aLanguageWithoutAPackIsSpokenInEnglish() = runTest {
        val r = Run(said("தண்ணீர் கசிவு ஏற்படுகிறது", language = "tamil"))
        val report = r.review()
        assertEquals("tamil", report.input.language)
        assertEquals(assets.questionBank.question("location", DialogueMode.PLAIN), r.manager.spoken.first { it.field == "location" }.text)
        assertEquals(setOf("en"), r.out.languages.toSet())
        for (line in r.manager.spoken) assertFalse(line.text, LanguageDetect.hasNonLatin(line.text))
    }

    @Test fun anEnglishSessionIsUnchanged() = runTest {
        val r = Run(said("There is a water leak.", language = "en"))
        r.review()
        assertEquals(setOf("en"), r.out.languages.toSet())
        assertEquals(assets.questionBank.question("location", DialogueMode.PLAIN), r.manager.spoken.first { it.field == "location" }.text)
    }

    @Test fun theGreetingFollowsTheForcedLanguageBeforeAnyNarrationIsHeard() = runTest {
        val r = Run(null, DialogueConfig(useLlmRephrase = false, speakGreeting = true, language = "Hindi"))
        r.review() // the scripted narration is English: the greeting was Hindi, the rest English
        assertEquals(hi.bank.greeting, r.manager.spoken.first().text)
        assertEquals("hi", r.out.languages.first())
        assertEquals("en", r.out.languages.last())
    }

    @Test fun theRephraserIsNotUsedOutsideEnglish() = runTest {
        var calls = 0
        val rephraser = object : Rephraser {
            override suspend fun rephrase(req: RephraseRequest): String? {
                calls++
                return "Where was this?"
            }
        }
        val r = Run(said("पानी लीक हो रहा है", language = "hindi"), DialogueConfig(useLlmRephrase = true), rephraser)
        r.review()
        assertEquals(0, calls)
        assertEquals(hi.bank.question("location", DialogueMode.PLAIN), r.manager.spoken.first { it.field == "location" }.text)
        val en = Run(said("There is a water leak.", language = "en"), DialogueConfig(useLlmRephrase = true), rephraser)
        en.review()
        assertTrue("the English session still rephrases", calls > 0)
    }
}
