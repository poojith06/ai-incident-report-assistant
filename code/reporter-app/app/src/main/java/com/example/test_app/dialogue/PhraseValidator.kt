package com.example.test_app.dialogue

import com.example.test_app.domain.service.TextMatch

/** Outcome of [PhraseValidator.validate]. [logValue] is the `validator` value of the JSONL log (`pass` / `fail:<reason>`). */
sealed interface ValidationResult {
    val logValue: String

    data object Pass : ValidationResult {
        override val logValue = "pass"
    }

    data class Fail(val reason: String) : ValidationResult {
        override val logValue get() = "fail:$reason"
    }
}

/**
 * Checks a generated (rephrased) question before it may be spoken (Spec v2 section 8.3, checks 1-5; the 1500 ms
 * timeout, check 6, is enforced by `DialogueManager`). Any failure means the template is spoken instead.
 * See `doc/DIALOGUE.md`.
 */
class PhraseValidator(private val blocked: BlockedPhrases, private val fieldKeywords: FieldKeywords) {

    fun validate(field: String, rawText: String?): ValidationResult {
        val text = rawText?.replace('\u2019', '\'')
        if (text.isNullOrBlank()) return ValidationResult.Fail("blank")
        val marks = text.count { it == '?' }
        if (marks != 1) return ValidationResult.Fail(if (marks == 0) "no_question_mark" else "multiple_questions")
        val words = QuestionBank.wordCount(text)
        if (words > QuestionBank.MAX_QUESTION_WORDS) return ValidationResult.Fail("too_long:$words")
        blocked.phrases.firstOrNull { TextMatch.contains(text, it) }?.let { return ValidationResult.Fail("blocked_phrase:$it") }
        blocked.stems.firstOrNull { TextMatch.contains(text, it, prefix = true) }?.let { return ValidationResult.Fail("blocked_phrase:$it") }
        if (LanguageDetect.hasNonLatin(text)) return ValidationResult.Fail("non_latin_script")
        val firstWord = TextMatch.tokens(text).firstOrNull().orEmpty()
        if (firstWord in blocked.whyStarters) return ValidationResult.Fail("starts_with_why")
        val keywords = fieldKeywords.byField[field] ?: return ValidationResult.Fail("unknown_field")
        val lower = text.lowercase()
        if (keywords.none { lower.contains(it) }) return ValidationResult.Fail("off_topic")
        return ValidationResult.Pass
    }
}
