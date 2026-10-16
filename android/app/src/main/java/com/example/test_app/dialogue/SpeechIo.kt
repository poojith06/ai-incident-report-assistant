package com.example.test_app.dialogue

/** One recognised utterance. [confidence] is 0..1; below `DialogueConfig.minAsrConfidence` the answer is `unverified`. */
data class AsrResult(val text: String, val confidence: Float, val language: String)

/** Speaks text; suspends until finished. `DialogueManager` is the only caller (Spec v2 section 9). */
interface SpeechOut {
    suspend fun speak(text: String, language: String)
}

/** Listens for one utterance. */
interface SpeechIn {
    /** Returns null when nothing was said within [silenceTimeoutSec] (= no response). Never retried by the dialogue. */
    suspend fun listen(silenceTimeoutSec: Int = 8, maxSeconds: Int = 30): AsrResult?
}

/**
 * Optional capability of a [SpeechIn]: told which dialogue field the next utterance answers (null = narration,
 * safety check or read-back). Real speech recognition MUST ignore it; the Phase 1 mock STT uses it to pick a
 * scripted answer (see `doc/SERVICES_AND_MOCKS.md`).
 */
interface FieldAwareSpeechIn : SpeechIn {
    fun expectAnswerFor(field: String?)
}

/** [FieldAwareSpeechIn.expectAnswerFor] value while waiting for the reply to the safety check. */
const val ANSWER_HINT_SAFETY = "safety"
