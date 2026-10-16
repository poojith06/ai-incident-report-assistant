package com.example.test_app.dialogue

/**
 * Tunables of the follow-up dialogue (Spec v2 sections 4.5, 4.6, 8.3, 8.4). See `doc/DIALOGUE.md`.
 */
data class DialogueConfig(
    /** Feature flag (Spec v2 section 8.4). `false` = speak the templates verbatim. */
    val useLlmRephrase: Boolean = true,
    /**
     * Language of the lines spoken BEFORE the first narration is heard (the greeting): the forced spoken language of the Settings, else `en`.
     * After the narration the language pack of the language that was heard is used (`DialogueAssets.pack`).
     */
    val language: String = "en",
    /**
     * STRICT, absolute cap on questions per report, whatever the mode: every spoken prompt that expects an answer counts, i.e.
     * the planner's field questions plus the URGENT safety check. The read-back is a statement (no question, no listening), like
     * the greeting, opening, defer and closing lines. Per-mode caps ([DialogueMode.maxQuestions]) apply only when lower, so URGENT is
     * the safety check plus one field. Fields still wanted after the cap are `missing_flagged`. Enforced in `DialogueManager`
     * (hard stop + debug assertion). ADR-048.
     */
    val maxQuestionsTotal: Int = 2,
    /**
     * User setting "Follow-up questions" (default on). Off: NO question is asked at all (no safety check, no field question, no
     * opening line); after the extraction the dialogue goes straight to the read-back statement and Review, the fields it would have
     * asked about are `missing_flagged`, an URGENT report still raises the emergency flag and the severity floors, the greeting is
     * still spoken and the answer recording never starts. See `doc/DIALOGUE.md`.
     */
    val followUpsEnabled: Boolean = true,
    /**
     * User setting "Hands-free answers" (default on). On: the microphone starts by itself after each question. Off: the dialogue waits
     * (up to [greetingSilenceSec]) for a mic tap before listening; no tap = `no_response`. Only relevant while [followUpsEnabled].
     */
    val handsFreeAnswers: Boolean = true,
    /**
     * Speak "Hi, describe your incident." before the first narration (default false, ADR-052: overrides Spec v2 section 4.6). Off, the
     * first mic tap starts recording at once: no speech, no wait for a TTS timeout. The question bank keeps the greeting line for this flag.
     */
    val speakGreeting: Boolean = false,
    /** Silence before the first narration after which the on-screen "tap the mic" hint is shown (and, with [speakGreeting], the greeting was never repeated). */
    val greetingSilenceSec: Int = 10,
    /** Silence after a question before the answer is recorded as `no_response`. */
    val answerSilenceSec: Int = 8,
    /** Maximum length of one listened answer. */
    val maxListenSec: Int = 30,
    /** Rephrase call budget; on timeout the template is spoken. */
    val rephraseTimeoutMs: Long = 1500,
    /** An ASR result below this confidence is stored as `unverified` and never sent to extraction. */
    val minAsrConfidence: Float = 0.5f,
)
