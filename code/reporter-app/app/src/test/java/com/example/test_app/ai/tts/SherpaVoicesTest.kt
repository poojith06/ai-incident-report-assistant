package com.example.test_app.ai.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaVoicesTest {
    private val telugu = "మూడవ అంతస్తులో మంటలు చెలరేగాయి. దయచేసి వెంటనే సహాయం పంపండి."
    private val hindi = "तीसरी मंजिल पर आग लगी है। कृपया तुरंत मदद भेजें।"
    private val EDGE = " .?!,;:-\"'()[]\u0964\u0965\u2026"
    private val assamese = "তৃতীয় মহলাত জুই লাগিছে। অনুগ্ৰহ কৰি সোনকালে সহায় পঠিয়াওক।"
    private val odia = "ତୃତୀୟ ମହଲାରେ ନିଆଁ ଲାଗିଛି। ଦୟାକରି ଶୀଘ୍ର ସାହାଯ୍ୟ ପଠାନ୍ତୁ।"
    private val arabic = "\u0645\u0631\u062d\u0628\u0627 \u0628\u0627\u0644\u0639\u0627\u0644\u0645"

    // ---- routing ----------------------------------------------------------------------------------------------------------------------

    @Test fun latinTextGoesToPiperWhatEverTheHint() {
        val p = SherpaVoices.plan("Fire reported on the third floor. Severity high.", languageHint = "telugu")
        assertEquals(VoiceEngine.PIPER, p.engine)
        assertEquals("en", p.language)
        assertEquals(TtsModel.PIPER, p.model)
        assertEquals(listOf("Fire reported on the third floor. Severity high."), p.chunks)
    }

    @Test fun piperKeepsTheTextButCollapsesWhitespace() {
        assertEquals(listOf("Hi, describe your incident."), SherpaVoices.plan("  Hi,\n describe   your incident. ").chunks)
    }

    @Test fun teluguGoesToRasaWithItsSpeaker() {
        val p = SherpaVoices.plan(telugu, "telugu")
        assertEquals(VoiceEngine.RASA, p.engine)
        assertEquals("te", p.language)
        assertEquals(19, p.speakerId)
        assertEquals(0, p.emotionId)
        assertEquals(TtsModel.RASA, p.model)
    }

    @Test fun everyScriptWithAVoiceHasADefaultSpeakerInRange() {
        for ((script, languages) in SherpaVoices.SCRIPT_LANGUAGES) for (lang in languages) {
            if (lang in SherpaVoices.NO_RASA_SPEAKER) continue
            val id = SherpaVoices.DEFAULT_SPEAKERS[lang]
            assertTrue("$script/$lang has no speaker", id != null && id in 0..SherpaVoices.MAX_SPEAKER)
        }
    }

    @Test fun scriptDecidesWhenThereIsNoHint() {
        assertEquals("hi", SherpaVoices.languageOf(hindi, null))
        assertEquals("bn", SherpaVoices.languageOf(assamese, null)) // the Bengali script alone is Bengali
        assertEquals("ta", SherpaVoices.languageOf("தயவுசெய்து உடனடியாக உதவி அனுப்புங்கள்", ""))
        assertEquals("kn", SherpaVoices.languageOf("ದಯವಿಟ್ಟು ತಕ್ಷಣ ಸಹಾಯ ಕಳುಹಿಸಿ", null))
        assertEquals("ml", SherpaVoices.languageOf("ദയവായി ഉടൻ സഹായം അയയ്ക്കുക", null))
        assertEquals("pa", SherpaVoices.languageOf("ਕਿਰਪਾ ਕਰਕੇ ਤੁਰੰਤ ਮਦਦ ਭੇਜੋ", null))
    }

    @Test fun theSpeechModelsLanguageNameChoosesAmongTheLanguagesOfAScript() {
        assertEquals("mr", SherpaVoices.languageOf(hindi, "Marathi"))
        assertEquals("ne", SherpaVoices.languageOf(hindi, "nepali"))
        assertEquals("as", SherpaVoices.languageOf(assamese, "Assamese"))
        assertEquals("bn", SherpaVoices.languageOf(assamese, "bengali"))
        assertEquals(12, SherpaVoices.plan(hindi, "marathi").speakerId)
        assertEquals(0, SherpaVoices.plan(assamese, "assamese").speakerId)
    }

    @Test fun aHintThatContradictsTheScriptIsIgnored() {
        assertEquals("te", SherpaVoices.languageOf(telugu, "tamil"))
        assertEquals("hi", SherpaVoices.languageOf(hindi, "english"))
        assertEquals("hi", SherpaVoices.languageOf(hindi, "klingon"))
    }

    @Test fun hindiUsesTheHindiDefaultSpeaker() {
        val p = SherpaVoices.plan(hindi, "hindi")
        assertEquals(VoiceEngine.RASA, p.engine)
        assertEquals(SherpaVoices.DEFAULT_SPEAKERS.getValue("hi"), p.speakerId)
    }

    @Test fun languagesWithoutAVoiceFallBackToTheSystemEngine() {
        val arabicPlan = SherpaVoices.plan(arabic, "arabic")
        assertEquals(VoiceEngine.SYSTEM, arabicPlan.engine)
        assertNull(arabicPlan.model)
        assertEquals(VoiceEngine.SYSTEM, SherpaVoices.plan("Привет, мир", null).engine)
        assertEquals(VoiceEngine.SYSTEM, SherpaVoices.plan("你好，世界", null).engine)
        // Odia and Gujarati script are in the tokens but the model has no speaker for them
        assertEquals(VoiceEngine.SYSTEM, SherpaVoices.plan(odia, "odia").engine)
        assertEquals("or", SherpaVoices.plan(odia, "odia").language)
        assertEquals(VoiceEngine.SYSTEM, SherpaVoices.plan("કૃપા કરીને તરત મદદ મોકલો", "gujarati").engine)
    }

    @Test fun theDeveloperMenuSpeakerOverridesTheLanguageSpeakerAndEnablesUnvoicedScripts() {
        val p = SherpaVoices.plan(telugu, "telugu", RasaVoiceChoice(speakerId = 500, emotionId = 5))
        assertEquals(500, p.speakerId)
        assertEquals(5, p.emotionId)
        assertEquals(VoiceEngine.RASA, SherpaVoices.plan(odia, "odia", RasaVoiceChoice(speakerId = 3)).engine)
        // the override never touches English
        assertEquals(VoiceEngine.PIPER, SherpaVoices.plan("Hello there", null, RasaVoiceChoice(speakerId = 3, emotionId = 5)).engine)
    }

    @Test fun outOfRangeSpeakerAndEmotionAreIgnored() {
        val p = SherpaVoices.plan(telugu, "telugu", RasaVoiceChoice(speakerId = 5000, emotionId = 99))
        assertEquals(19, p.speakerId)
        assertEquals(0, p.emotionId)
        assertEquals(19, SherpaVoices.plan(telugu, "telugu", RasaVoiceChoice(speakerId = -3)).speakerId)
        assertEquals(1023, SherpaVoices.plan(telugu, "telugu", RasaVoiceChoice(speakerId = 1023)).speakerId)
        assertEquals(31, SherpaVoices.plan(telugu, "telugu", RasaVoiceChoice(emotionId = 31)).emotionId)
    }

    @Test fun mixedTextFollowsTheDominantScript() {
        assertEquals("te", SherpaVoices.languageOf("కె.ఆర్. బిల్డింగ్ KRB లో నీటి లీక్ అవుతోంది", null))
        assertEquals("en", SherpaVoices.languageOf("Fire at the కె block of the building near the gate", null))
    }

    @Test fun blankTextHasNoChunks() {
        assertTrue(SherpaVoices.plan("   \n ").chunks.isEmpty())
        assertTrue(SherpaVoices.rasaChunks("  ").isEmpty())
        assertTrue(SherpaVoices.rasaChunks("...").isEmpty())
    }

    // ---- Rasa text preparation --------------------------------------------------------------------------------------------------------

    @Test fun trailingSentencePunctuationIsStripped() {
        assertEquals(listOf("మూడవ అంతస్తులో మంటలు చెలరేగాయి", "దయచేసి వెంటనే సహాయం పంపండి"), SherpaVoices.rasaChunks(telugu))
        assertEquals(listOf("तीसरी मंजिल पर आग लगी है", "कृपया तुरंत मदद भेजें"), SherpaVoices.rasaChunks(hindi))
    }

    @Test fun noChunkEndsOrStartsWithPunctuation() {
        val chunks = SherpaVoices.rasaChunks("\"మూడవ అంతస్తులో మంటలు చెలరేగాయి!\" దయచేసి వెంటనే సహాయం పంపండి?? ... ,")
        assertTrue(chunks.isNotEmpty())
        for (c in chunks) {
            assertTrue("'$c'", c.first() !in EDGE && c.last() !in EDGE)
        }
    }

    @Test fun repeatedEndersAndEllipsisAreOneBoundary() {
        assertEquals(
            listOf("మూడవ అంతస్తులో మంటలు చెలరేగాయి", "దయచేసి వెంటనే సహాయం పంపండి"),
            SherpaVoices.rasaChunks("మూడవ అంతస్తులో మంటలు చెలరేగాయి!!! దయచేసి వెంటనే సహాయం పంపండి…"),
        )
    }

    @Test fun anAbbreviationNeitherSplitsNorLeavesATinyChunk() {
        // sherpa would split at both dots and speak "కె" and "ఆర్" as separate clicks
        assertEquals(listOf("కెఆర్ బిల్డింగ్ లో నీటి లీక్ అవుతోంది"), SherpaVoices.rasaChunks("కె.ఆర్. బిల్డింగ్ లో నీటి లీక్ అవుతోంది"))
        assertEquals(listOf("కెఆర్ బిల్డింగ్ లో నీటి లీక్ అవుతోంది"), SherpaVoices.rasaChunks("కె.ఆర్. బిల్డింగ్ లో నీటి లీక్ అవుతోంది."))
    }

    @Test fun aShortSentenceIsJoinedToTheNextOneButTheLastStandsAlone() {
        assertEquals(listOf("అవును మూడవ అంతస్తులో మంటలు చెలరేగాయి"), SherpaVoices.rasaChunks("అవును. మూడవ అంతస్తులో మంటలు చెలరేగాయి."))
        assertEquals(listOf("మూడవ అంతస్తులో మంటలు చెలరేగాయి", "ధన్యవాదాలు"), SherpaVoices.rasaChunks("మూడవ అంతస్తులో మంటలు చెలరేగాయి. ధన్యవాదాలు."))
        assertEquals(listOf("ధన్యవాదాలు"), SherpaVoices.rasaChunks("ధన్యవాదాలు."))
    }

    @Test fun innerCommasStayAndDecimalsDoNotSplit() {
        assertEquals(listOf("మూడవ అంతస్తులో, మంటలు చెలరేగాయి"), SherpaVoices.rasaChunks("మూడవ అంతస్తులో, మంటలు చెలరేగాయి,"))
        assertEquals(listOf("ఉష్ణోగ్రత 3 5 డిగ్రీలు ఉంది"), SherpaVoices.rasaChunks("ఉష్ణోగ్రత 3.5 డిగ్రీలు ఉంది."))
    }

    @Test fun newlinesBecomeSpacesAndQuestionMarksSplitToo() {
        assertEquals(
            listOf("మూడవ అంతస్తులో మంటలు చెలరేగాయి", "మీరు ఇప్పుడు సురక్షితంగా ఉన్నారా"),
            SherpaVoices.rasaChunks("మూడవ అంతస్తులో మంటలు చెలరేగాయి.\nమీరు ఇప్పుడు\n సురక్షితంగా ఉన్నారా?"),
        )
    }

    @Test fun rasaPlanCarriesThePreparedChunks() {
        val p = SherpaVoices.plan(hindi, "hindi")
        assertEquals(listOf("तीसरी मंजिल पर आग लगी है", "कृपया तुरंत मदद भेजें"), p.chunks)
    }

    // ---- speed ------------------------------------------------------------------------------------------------------------------------

    @Test fun speedFollowsTheSpeechRateSetting() {
        assertEquals(0.8f, SherpaVoices.speed(0.8f))
        assertEquals(1.0f, SherpaVoices.speed(1.0f))
        assertEquals(1.25f, SherpaVoices.speed(1.25f))
    }

    @Test fun speedIsBoundedAndNeverNonPositive() {
        assertEquals(0.5f, SherpaVoices.speed(0.1f))
        assertEquals(2.0f, SherpaVoices.speed(9f))
        assertEquals(1.0f, SherpaVoices.speed(0f))
        assertEquals(1.0f, SherpaVoices.speed(-1f))
        assertEquals(1.0f, SherpaVoices.speed(Float.NaN))
    }

    // ---- script runs and per-segment routing ------------------------------------------------------------------------------------------

    @Test fun aTextOfOneScriptIsOneRun() {
        assertEquals(listOf("Fire on the third floor."), SherpaVoices.scriptRuns("Fire on the third floor."))
        assertEquals(listOf(hindi), SherpaVoices.scriptRuns(hindi))
        assertTrue(SherpaVoices.scriptRuns("  ").isEmpty())
        assertEquals(listOf("2026"), SherpaVoices.scriptRuns("2026")) // digits only: one run
    }

    @Test fun latinAndNonLatinRunsAlternateAndKeepTheirPunctuation() {
        val runs = SherpaVoices.scriptRuns("दर्ज किया: KRB washroom, third floor, समय अभी का रखा गया है। कृपया जाँचें।")
        assertEquals(listOf("दर्ज किया: ", "KRB washroom, third floor, ", "समय अभी का रखा गया है। कृपया जाँचें।"), runs)
        assertEquals("मैंने Conference Room 2 में दर्ज किया", SherpaVoices.scriptRuns("मैंने Conference Room 2 में दर्ज किया").joinToString("").trim().let { it })
    }

    @Test fun digitsAndPunctuationStayWithTheRunBeforeThem() {
        assertEquals(listOf("Room 2, ", "में 3 बजे"), SherpaVoices.scriptRuns("Room 2, में 3 बजे"))
        assertEquals(listOf("\"", "x"), listOf("\"", "x")) // sanity of the helper below
        val leading = SherpaVoices.scriptRuns("(KRB) तीसरी मंजिल")
        assertEquals(listOf("(KRB) ", "तीसरी मंजिल"), leading)
    }

    @Test fun aHindiSentenceWithAnEnglishPlaceNameGetsTwoVoices() {
        val segs = SherpaVoices.segments("आग लगी है KRB building में, कृपया मदद भेजें।", "hindi")
        assertEquals(listOf(VoiceEngine.RASA, VoiceEngine.PIPER, VoiceEngine.RASA), segs.map { it.engine })
        assertEquals(listOf("hi", "en", "hi"), segs.map { it.language })
        assertEquals(SherpaVoices.DEFAULT_SPEAKERS.getValue("hi"), segs[0].speakerId)
        assertEquals(listOf("KRB building"), segs[1].chunks.map { it.trimEnd() }.map { it.substringBefore(" में") }.map { it.trim() })
        assertEquals(listOf("आग लगी है"), segs[0].chunks)
    }

    @Test fun theLanguageHintAppliesToTheNonLatinRunsOnly() {
        val segs = SherpaVoices.segments("आग लगी है KRB building", "marathi")
        assertEquals(listOf("mr", "en"), segs.map { it.language })
        assertEquals(12, segs[0].speakerId)
        assertEquals(listOf("te", "en", "te"), SherpaVoices.segments("మంటలు KRB building లో నీటి లీక్ అవుతోంది", "hindi").map { it.language })
    }

    @Test fun pausesDependOnWhatEndsTheRun() {
        val segs = SherpaVoices.segments("आग लगी है। KRB building, తీ", null)
        // run 1 ends the sentence, run 2 ends a clause, the last run has no pause after it
        val three = SherpaVoices.segments("आग लगी है। Room 2, कृपया मदद भेजें", null)
        assertEquals(listOf(SherpaVoices.SENTENCE_PAUSE_MS, SherpaVoices.CLAUSE_PAUSE_MS, 0), three.map { it.pauseAfterMs })
        assertEquals(0, segs.last().pauseAfterMs)
        assertEquals(SherpaVoices.SWITCH_PAUSE_MS, SherpaVoices.segments("आग Room 2 मदद", null).first().pauseAfterMs)
    }

    @Test fun aSegmentWithoutAVoiceIsSystemAndTheRestStaysRouted() {
        val segs = SherpaVoices.segments("Fire $arabic please", "arabic")
        assertTrue(segs.any { it.engine == VoiceEngine.SYSTEM })
        assertEquals(VoiceEngine.PIPER, segs.first().engine)
    }

    @Test fun aLatinOnlyTextWithANonEnglishHintIsEnglish() {
        val segs = SherpaVoices.segments("Report submitted. Reference I N C", "hindi")
        assertEquals(1, segs.size)
        assertEquals(VoiceEngine.PIPER, segs[0].engine)
    }

    @Test fun theDevanagariDandaNeverReachesPiper() {
        val p = SherpaVoices.plan("Room 2।")
        assertEquals(listOf("Room 2."), p.chunks)
    }

    // ---- resampling -------------------------------------------------------------------------------------------------------------------

    @Test fun resamplingKeepsDurationAndEndpoints() {
        val ramp = FloatArray(22050) { it / 22050f }
        val up = SherpaVoices.resample(ramp, 22050, 24000)
        assertEquals(24000, up.size)
        assertEquals(0f, up.first(), 1e-6f)
        assertEquals(ramp.last(), up.last(), 1e-3f)
        for (i in 1 until up.size) assertTrue("monotonic at $i", up[i] >= up[i - 1])
        assertEquals(ramp.size, SherpaVoices.resample(up, 24000, 22050).size)
        assertSame(ramp, SherpaVoices.resample(ramp, 22050, 22050))
        assertEquals(0, SherpaVoices.resample(FloatArray(0), 22050, 24000).size)
    }
}
