package com.example.test_app.ai.llama

import com.example.test_app.ai.AiTestSupport.fixture
import com.example.test_app.ai.AiTestSupport.notNull
import com.example.test_app.ai.ChatFormat
import com.example.test_app.ai.LlmOutputParser
import com.example.test_app.ai.MediaPart
import com.example.test_app.ai.Prompts
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gemma output cleanup, image maths and media packing. Fixtures are HAND-WRITTEN (`llm_outputs/README.txt`). */
class GemmaTextTest {
    // ---- transcript cleanup ----

    @Test fun plainTranscriptIsKeptAsIs() {
        assertEquals("There is a pipe leaking in front of me.", GemmaText.cleanTranscript("There is a pipe leaking in front of me.<turn|>"))
    }

    @Test fun labelQuotesAndTurnTokenAreRemoved() {
        assertEquals(
            "There is a pipe leaking in front of me. I am in K R B third floor in front of CVIT.",
            GemmaText.cleanTranscript(fixture("gemma_transcript_labelled.txt")),
        )
    }

    @Test fun emptyThoughtChannelAndWhitespaceAreFlattened() {
        assertEquals("an AC caught fire in vindya a2", GemmaText.cleanTranscript("<|channel>thought\n<channel|>\n an AC caught fire\n in vindya a2  \n"))
        assertEquals("", GemmaText.cleanTranscript("<|channel>thought\n<channel|><turn|>"))
        assertEquals("", GemmaText.cleanTranscript("   "))
    }

    @Test fun numbersAreNotRewritten() {
        assertEquals("a2 block and a two block", GemmaText.cleanTranscript("a2 block and a two block"))
        assertEquals("the pole has been down for 3 hours", GemmaText.cleanTranscript("\"the pole has been down for 3 hours\""))
    }

    // ---- photo description ----

    @Test fun photoJsonGivesDescriptionAndTags() {
        val p = notNull(GemmaText.parsePhoto(fixture("gemma_photo.txt")))
        assertEquals("A close-up of an electrical outlet on a white wall with smoke rising from it.", p.description)
        assertEquals(listOf("outlet", "smoke", "electrical", "wall"), p.tags)
    }

    @Test fun photoTagsAreLowercasedDedupedAndCapped() {
        val raw = " \"x\", \"tags\": [\"Fire\", \"fire\", \" Smoke \", 1, null, \"a\", \"b\", \"c\", \"d\"]}"
        val p = notNull(GemmaText.parsePhoto(raw))
        assertEquals(listOf("fire", "smoke", "1", "a", "b"), p.tags)
    }

    @Test fun photoPlainSentenceBecomesTheDescription() {
        val p = notNull(GemmaText.parsePhoto("A pipe is dripping.\nIt is wet.<turn|>"))
        assertEquals("A pipe is dripping.", p.description)
        assertTrue(p.tags.isEmpty())
    }

    @Test fun photoJsonWithoutADescriptionIsNothing() {
        assertNull(GemmaText.parsePhoto(" \"\", \"tags\": []}"))
        assertNull(GemmaText.parsePhoto("<|channel>thought\n<channel|>"))
    }

    // ---- Gemma replies through the existing parser ----

    @Test fun gemmaExtractionReplyParsesLikeQwens() {
        val plain = notNull(LlmOutputParser.parseObject(fixture("gemma_extract_plain.txt"), Prompts.EXTRACTION_PREFIX))
        assertEquals(setOf("incident_type", "severity", "location", "datetime", "persons_involved", "actions_taken", "description"), plain.keys)
        val channel = notNull(LlmOutputParser.parseObject(fixture("gemma_extract_empty_channel.txt"), Prompts.EXTRACTION_PREFIX))
        assertEquals("\"Fire\"", channel["incident_type"].toString())
        assertEquals("\"server room\"", channel["location"].toString())
    }

    @Test fun oneCallReplyHasTheNineKeys() {
        val json = notNull(LlmOutputParser.parseObject(fixture("gemma_multimodal_audio_photo.txt"), Prompts.MULTIMODAL_PREFIX))
        assertEquals(
            setOf("language", "transcript", "image_description", "incident_type", "severity", "location", "datetime", "persons_involved", "actions_taken", "description"),
            json.keys,
        )
        val photoOnly = notNull(LlmOutputParser.parseObject(fixture("gemma_multimodal_photo_only.txt"), Prompts.MULTIMODAL_PREFIX))
        assertEquals("\"\"", photoOnly["transcript"].toString())
        assertEquals("\"Fire\"", photoOnly["incident_type"].toString())
    }

    @Test fun truncatedOneCallReplyKeepsTheCompletePairs() {
        val json = notNull(LlmOutputParser.parseObject(fixture("gemma_multimodal_truncated.txt"), Prompts.MULTIMODAL_PREFIX))
        assertEquals(setOf("language", "transcript"), json.keys)
    }

    // ---- transcript + language reply (ADR-051) ----

    private fun reply(raw: String) = GemmaText.parseTranscriptReply(raw)

    @Test fun cleanTwoLineReply() {
        val r = reply("Language: Tamil\nTranscript: கேஆர்பி டேங்க் ஓவர்ஃப்ளோ ஆகுது")
        assertEquals("கேஆர்பி டேங்க் ஓவர்ஃப்ளோ ஆகுது", r.text)
        assertEquals("tamil", r.language)
    }

    @Test fun englishIsStoredAsEn() {
        val r = reply("Language: English\nTranscript: A pipe is leaking near the cafeteria.")
        assertEquals("A pipe is leaking near the cafeteria.", r.text)
        assertEquals("en", r.language)
    }

    @Test fun languageNameIsStoredAsReportedLowercased() {
        for (n in listOf("Assamese", "Odia", "Bengali", "Marathi", "Kannada")) {
            assertEquals(n.lowercase(), reply("Language: $n\nTranscript: x y").language)
        }
        assertEquals("tamil", reply("language: TAMIL.\ntranscript: x").language)
        assertEquals("hindi", reply("Language: Hindi (Devanagari)\nTranscript: x").language)
    }

    @Test fun missingLanguageLineMeansWholeReplyIsTheTranscriptAndLanguageUnknown() {
        val r = reply("There is a pipe leaking in front of me.<turn|>")
        assertEquals("There is a pipe leaking in front of me.", r.text)
        assertNull(r.language)
        val labelled = reply("Transcript: the pole is down")
        assertEquals("the pole is down", labelled.text)
        assertNull(labelled.language)
        assertNull(reply("Language: unknown\nTranscript: abc").language)
        assertNull(reply("Language: N/A\nTranscript: abc").language)
        assertEquals(GemmaText.TranscriptReply("", null), reply("   "))
    }

    @Test fun chatterBeforeAndAfterTheLabelsIsHandled() {
        val r = reply("Sure! Here is what I heard:\n\nLanguage: Odia\nTranscript: ଏଠାରେ ପାଣି ଲିକ୍ ହେଉଛି\n")
        assertEquals("ଏଠାରେ ପାଣି ଲିକ୍ ହେଉଛି", r.text)
        assertEquals("odia", r.language)
        val md = reply("**Language:** Bengali\n**Transcript:** \"জল পড়ছে\"")
        assertEquals("জল পড়ছে", md.text)
        assertEquals("bengali", md.language)
        val fenced = reply("```\nLanguage: Tamil\nTranscript: ஒரு குழாய்\n```")
        assertEquals("ஒரு குழாய்", fenced.text)
        assertEquals("tamil", fenced.language)
    }

    @Test fun thinkAndChannelTokensAreRemoved() {
        val r = reply("<|channel>thought\nlet me listen<channel|>Language: Assamese\nTranscript: পানী পৰি আছে<turn|>")
        assertEquals("পানী পৰি আছে", r.text)
        assertEquals("assamese", r.language)
        val think = reply("<think>hmm</think>\nLanguage: English\nTranscript: fire in lab 3")
        assertEquals("fire in lab 3", think.text)
        assertEquals("en", think.language)
        assertEquals("", reply("<|channel>thought\n<channel|><turn|>").text)
    }

    @Test fun multiLineTranscriptIsJoinedIntoOneLine() {
        val r = reply("Language: Tamil\nTranscript: first part\nsecond part\n\nthird part")
        assertEquals("first part second part third part", r.text)
        assertEquals("tamil", r.language)
    }

    @Test fun languageLineWithoutTranscriptLabelTakesTheFollowingLines() {
        val r = reply("Language: Telugu\nనీళ్లు లీక్ అవుతున్నాయి\nరెండో అంతస్తు")
        assertEquals("నీళ్లు లీక్ అవుతున్నాయి రెండో అంతస్తు", r.text)
        assertEquals("telugu", r.language)
    }

    @Test fun swappedOrSingleLineLabelsAreRead() {
        val swapped = reply("Transcript: water leak\nLanguage: English")
        assertEquals("water leak", swapped.text)
        assertEquals("en", swapped.language)
        val one = reply("Language: Odia Transcript: ପାଣି")
        assertEquals("ପାଣି", one.text)
        assertEquals("odia", one.language)
    }

    @Test fun aLanguageLineWithNothingElseGivesAnEmptyTranscript() {
        val r = reply("Language: Tamil")
        assertEquals("", r.text)
        assertEquals("tamil", r.language)
    }

    @Test fun theInstructionIsLanguageAgnosticAndAsksForTheTwoLineFormat() {
        val p = Prompts.TRANSCRIBE_INSTRUCTION
        assertTrue("Language: <" in p && "Transcript: <" in p)
        assertTrue("own native script" in p && "Do not translate" in p)
        assertTrue("Tamil" in p && "Assamese" in p && "Odia" in p)
    }

    // ---- forced spoken language (ADR-054) ----

    @Test fun forcedReplyIsPlainTextWithTheChosenLanguage() {
        val r = GemmaText.forcedTranscriptReply("இமாலியா வகுப்பு பல்புட்டி<turn|>", "Tamil")
        assertEquals("இமாலியா வகுப்பு பல்புட்டி", r.text)
        assertEquals("tamil", r.language)
        assertEquals("en", GemmaText.forcedTranscriptReply("a pipe is leaking", "English").language)
        assertEquals("odia", GemmaText.forcedTranscriptReply("ପାଣି", "Odia").language)
    }

    @Test fun forcedReplyIgnoresTheTwoLineFormatEvenWhenTheWordsAppear() {
        // in forced mode the whole reply is the transcript (a speaker may really say "language")
        assertEquals("language: tamil", GemmaText.forcedTranscriptReply("language: tamil", "Tamil").text.lowercase())
        assertEquals("the pole is down", GemmaText.forcedTranscriptReply("Transcript: the pole is down", "English").text)
        assertEquals("", GemmaText.forcedTranscriptReply("<|channel>thought\n<channel|><turn|>", "Tamil").text)
    }

    @Test fun theSttPicksTheForcedPromptAndReplyParserOnlyWhenALanguageIsSet() {
        val audio = MediaPart.Audio(FloatArray(4))
        assertEquals(Prompts.TRANSCRIBE_INSTRUCTION, GemmaSpeechToText.prompt(null, audio).messages.single().text)
        assertTrue(GemmaSpeechToText.prompt("Telugu", audio).messages.single().text.startsWith("This audio is spoken in Telugu."))
        // auto: the two-line reply names the language; forced: the user's choice wins, the reply stays plain text
        assertEquals("tamil", GemmaSpeechToText.parseReply("Language: Tamil\nTranscript: x", null).language)
        assertEquals("telugu", GemmaSpeechToText.parseReply("నీళ్ళు లీక్", "Telugu").language)
        assertEquals("నీళ్ళు లీక్", GemmaSpeechToText.parseReply("నీళ్ళు లీక్", "Telugu").text)
    }

    // ---- image scaling ----

    @Test fun fitKeepsTheAspectRatioAndNeverUpscales() {
        assertEquals(349 to 512, ImageScaling.fit(2746, 4032))
        assertEquals(512 to 288, ImageScaling.fit(1920, 1080))
        assertEquals(288 to 512, ImageScaling.fit(1080, 1920))
        assertEquals(300 to 200, ImageScaling.fit(300, 200))
        assertEquals(512 to 512, ImageScaling.fit(512, 512))
        assertEquals(512 to 1, ImageScaling.fit(5000, 3))
    }

    @Test fun sampleSizeIsAPowerOfTwoThatKeepsAtLeast512Px() {
        assertEquals(4, ImageScaling.sampleSize(2746, 4032)) // 4032 / 4 = 1008 >= 512, / 8 = 504 < 512
        assertEquals(1, ImageScaling.sampleSize(1000, 700))
        assertEquals(2, ImageScaling.sampleSize(1024, 768))
        assertEquals(1, ImageScaling.sampleSize(300, 200))
    }

    @Test fun exifOrientationMapsToRotation() {
        assertEquals(0, ImageScaling.exifRotationDegrees(1))
        assertEquals(90, ImageScaling.exifRotationDegrees(6))
        assertEquals(180, ImageScaling.exifRotationDegrees(3))
        assertEquals(270, ImageScaling.exifRotationDegrees(8))
        assertEquals(0, ImageScaling.exifRotationDegrees(0))
        assertEquals(0, ImageScaling.exifRotationDegrees(2)) // the mirror of 2 is separate (exifMirrored)
    }

    @Test fun planGivesSizesBeforeAndAfterRotation() {
        // a portrait photo stored as 4032x3024 with "rotate 90": shown as 3024x4032
        val p = ImageScaling.plan(4032, 3024, 90)
        assertEquals(ImageScaling.Plan(scaledWidth = 512, scaledHeight = 384, finalWidth = 384, finalHeight = 512), p)
        assertEquals(ImageScaling.Plan(512, 288, 512, 288), ImageScaling.plan(1920, 1080, 0))
        assertEquals(ImageScaling.Plan(200, 100, 200, 100), ImageScaling.plan(200, 100, 180))
    }

    @Test fun argbBecomesPackedRgbWithoutAlpha() {
        val rgb = ImageScaling.argbToRgb(intArrayOf(0xFF102030.toInt(), 0x80FFEEDD.toInt()))
        assertArrayEquals(byteArrayOf(0x10, 0x20, 0x30, 0xFF.toByte(), 0xEE.toByte(), 0xDD.toByte()), rgb)
    }

    // ---- media packing and audio clipping ----

    @Test fun mediaIsPackedInPromptOrder() {
        val a = MediaPart.Audio(floatArrayOf(0.1f, 0.2f))
        val i1 = MediaPart.Image(ByteArray(2 * 1 * 3) { it.toByte() }, 2, 1)
        val i2 = MediaPart.Image(ByteArray(1 * 3 * 3), 1, 3)
        val packed = PackedMedia.pack(listOf(i1, a, i2))
        assertArrayEquals(intArrayOf(0, 1, 0), packed.kinds)
        assertEquals(1, packed.audio.size)
        assertSame(a.pcm16k, packed.audio[0])
        assertArrayEquals(intArrayOf(2, 1, 1, 3), packed.imageDims)
        assertEquals(2, packed.images.size)
        assertSame(i1.rgb, packed.images[0])
    }

    @Test fun badImageSizeIsRejected() {
        try {
            MediaPart.Image(ByteArray(5), 2, 1)
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun clipCutsAtTheLimitAndKeepsShortAudio() {
        val pcm = FloatArray(16_000 * 100)
        assertEquals(16_000 * 90, GemmaSpeechToText.clip(pcm, 90_000).size)
        val short = FloatArray(16_000)
        assertSame(short, GemmaSpeechToText.clip(short, 90_000))
    }

    @Test fun gemmaAndChatMlStayDistinctFormats() {
        val p = Prompts.rephrase(com.example.test_app.dialogue.RephraseRequest("location", com.example.test_app.dialogue.DialogueMode.PLAIN, "Where did it happen?", "water", "en"))
        assertNotNull(p)
        assertTrue(p.render(ChatFormat.CHATML).endsWith("<think>\n\n</think>\n\n"))
        assertTrue(p.render(ChatFormat.GEMMA4).endsWith("<turn|>\n<|turn>model\n"))
    }
}
