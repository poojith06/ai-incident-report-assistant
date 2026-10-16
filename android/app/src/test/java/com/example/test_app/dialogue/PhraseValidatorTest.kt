package com.example.test_app.dialogue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class PhraseValidatorTest {
    private val a = TestAssets.assets
    private val v = PhraseValidator(a.blocked, a.fieldKeywords)

    private fun fails(field: String, text: String?, reasonPrefix: String) {
        val r = v.validate(field, text)
        assertTrue("expected failure for \"$text\" but got $r", r is ValidationResult.Fail)
        assertTrue("reason was ${(r as ValidationResult.Fail).reason}", r.reason.startsWith(reasonPrefix))
    }

    private fun passes(field: String, text: String) = assertEquals("\"$text\"", ValidationResult.Pass, v.validate(field, text))

    // ---- good outputs ----

    @Test fun goodRephrasings() {
        passes("location", "You mentioned the pantry. Which floor is that on?")
        passes("location", "Where in the building did this happen?")
        passes("injury", "Is everyone okay after the fall?")
        passes("time", "Roughly when did the spill happen?")
        passes("description", "What did you see near the printer?")
        passes("immediate_actions", "Has anyone been able to help yet?")
        passes("people_involved", "Who was involved? You can skip this.")
        passes("injury_type", "What kind of injury is it? You can skip this.")
    }

    @Test fun everyTemplateInTheBankPasses() {
        for ((where, text) in a.questionBank.allQuestions()) {
            val field = where.substringBefore('/')
            passes(field, text)
        }
    }

    // ---- bad outputs ----

    @Test fun blankOrNull() {
        fails("location", null, "blank")
        fails("location", "   ", "blank")
    }

    @Test fun mustHaveExactlyOneQuestionMark() {
        fails("location", "Tell me where this happened.", "no_question_mark")
        fails("location", "Where did it happen? And when was it?", "multiple_questions")
    }

    @Test fun twentyWordsOkTwentyOneFail() {
        val twenty = (1..19).joinToString(" ") { "where" } + " now?"
        passes("location", twenty)
        fails("location", "where " + twenty, "too_long")
    }

    @Test fun blockedPhrases() {
        fails("location", "Why did you go there, where was it?", "blocked_phrase")
        fails("injury", "Don't worry, was anyone hurt?", "blocked_phrase")
        fails("location", "Please answer, where was it?", "blocked_phrase")
        fails("injury", "Calm down. Is anyone hurt?", "blocked_phrase")
        fails("injury", "Help is on the way. Is anyone hurt?", "blocked_phrase")
        fails("injury_type", "Can I diagnose it, what kind of injury?", "blocked_phrase")
        fails("injury_type", "You need to see a doctor, what kind of injury is it?", "blocked_phrase")
    }

    @Test fun blockedPhrasesAreCaseInsensitive() {
        fails("injury", "DON'T WORRY, was anyone hurt?", "blocked_phrase")
        fails("injury", "Don\u2019t worry, was anyone hurt?", "blocked_phrase")
    }

    @Test fun anyImplicationOfRepeatingIsBlocked() {
        fails("location", "Let me ask again, where was it?", "blocked_phrase")
        fails("location", "Where did it happen again?", "blocked_phrase")
        fails("location", "I'll ask again. Where did it happen?", "blocked_phrase")
    }

    @Test fun againInsideAnotherWordIsFine() {
        passes("location", "Where is that against the wall?")
    }

    @Test fun startsWithWhy() {
        fails("location", "Why is the room where this happened?", "starts_with_why")
        fails("location", "Kyun hua wahan, where?", "starts_with_why")
        fails("location", "Enduku, where was it?", "starts_with_why")
    }

    @Test fun mustMentionTheTargetField() {
        fails("location", "Was anyone hurt?", "off_topic")
        fails("injury", "Where did this happen?", "off_topic")
        fails("time", "Which building is it?", "off_topic")
    }

    @Test fun unknownFieldFails() {
        fails("bogus", "Where is it?", "unknown_field")
    }

    @Test fun logValues() {
        assertEquals("pass", ValidationResult.Pass.logValue)
        assertEquals("fail:blank", ValidationResult.Fail("blank").logValue)
    }

    // ---- prompt ----

    @Test fun rephrasePromptCarriesFieldTemplateAndOnlyTheLastSixtyWords() {
        val words = (1..100).joinToString(" ") { "w$it" }
        val prompt = RephrasePrompt.render(RephraseRequest("location", DialogueMode.CARE, "Where did this happen?", words, "en"))
        assertTrue(prompt.contains("about the field: location"))
        assertTrue(prompt.contains("warm and gentle"))
        assertTrue(prompt.contains("Template to adapt: \"Where did this happen?\""))
        assertTrue(prompt.contains("w100") && prompt.contains("w41") && !prompt.contains("w40 "))
        assertTrue("prompt too long: ${prompt.length}", prompt.length / 3 <= 300)
    }

    // ---- assets ----

    @Test fun allThreeBanksLoadAndShareTheSameKeys() {
        val en = QuestionBank.parse(File(TestAssets.dir, "question_bank.en.json").readText())
        val hi = QuestionBank.parse(File(TestAssets.dir, "question_bank.hi.json").readText())
        val te = QuestionBank.parse(File(TestAssets.dir, "question_bank.te.json").readText())
        assertEquals(en.allQuestions().map { it.first }, hi.allQuestions().map { it.first })
        assertEquals(en.allQuestions().map { it.first }, te.allQuestions().map { it.first })
        assertEquals("hi", hi.language)
        // the hi / te banks are real translations now (needs_native_review), no longer English stubs behind TODO_TRANSLATE
        for (name in listOf("hi", "te")) {
            val text = File(TestAssets.dir, "question_bank.$name.json").readText()
            assertFalse(text.contains("TODO_TRANSLATE"))
            assertTrue(text.contains("\"needs_native_review\": true"))
        }
        assertEquals("devanagari", LanguageDetect.detect(hi.greeting))
        assertEquals("telugu", LanguageDetect.detect(te.greeting))
    }

    @Test fun everyEnglishQuestionHasOneQuestionMarkAndAtMostTwentyWords() {
        for ((where, text) in a.questionBank.allQuestions()) {
            assertEquals(where, 1, text.count { it == '?' })
            assertTrue("$where: $text", QuestionBank.wordCount(text) <= 20)
        }
    }

    @Test fun bankWithClarifyKeyIsRejected() {
        val text = File(TestAssets.dir, "question_bank.en.json").readText()
            .replace("\"location\": {", "\"location\": { \"clarify\": \"Sorry, can you repeat?\",")
        try {
            QuestionBank.parse(text)
            fail("expected DialogueAssetException")
        } catch (e: DialogueAssetException) {
            assertTrue(e.message!!.contains("clarify"))
        }
    }

    @Test fun bankWithTwoQuestionMarksIsRejected() {
        val text = File(TestAssets.dir, "question_bank.en.json").readText().replace("Where did this happen?", "Where did this happen? Really?")
        try {
            QuestionBank.parse(text)
            fail("expected DialogueAssetException")
        } catch (e: DialogueAssetException) {
            assertTrue(e.message!!.contains("exactly one"))
        }
    }

    @Test fun nonEnglishKeywordsAreMarkedForNativeReview() {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(File(TestAssets.dir, "mode_keywords.json").readText()) as kotlinx.serialization.json.JsonObject
        for (mode in listOf("URGENT", "CARE")) for (lang in listOf("hi", "te")) {
            val entry = (root[mode] as kotlinx.serialization.json.JsonObject)[lang] as kotlinx.serialization.json.JsonObject
            assertEquals("$mode/$lang", "true", entry["needs_native_review"].toString())
        }
        for (lang in listOf("hi", "te")) {
            assertEquals("negation/$lang", "true", ((root["negation"] as kotlinx.serialization.json.JsonObject)[lang] as kotlinx.serialization.json.JsonObject)["needs_native_review"].toString())
        }
    }

    @Test fun modeKeywordsContainTheSpecLists() {
        val k = a.modeKeywords
        for (w in listOf("fire", "smoke", "gas leak", "unconscious", "knife", "aag", "dhuan", "nippu")) assertTrue(w, w in k.urgent)
        for (w in listOf("hurt", "injured", "scared", "harassment", "chot", "debba")) assertTrue(w, w in k.care)
    }
}
