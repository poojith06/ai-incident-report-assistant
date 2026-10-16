package com.example.test_app.domain.config

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Speech rate of the spoken read-outs (user setting "Speech rate"). [value] goes to the TTS engine. */
enum class SpeechRate(val label: String, val value: Float) {
    SLOW("Slow", 0.8f),
    NORMAL("Normal", 1.0f),
    FAST("Fast", 1.25f);

    companion object {
        /** The rate with this [name], or [NORMAL] for null / unknown (a corrupted preference never breaks speech). */
        fun fromName(name: String?): SpeechRate = entries.firstOrNull { it.name == name } ?: NORMAL
    }
}

/**
 * User-facing preferences of the Settings screen (not the hidden developer menu, which has its own `DevSettings`). Pure data with
 * the defaults in one place; persisted by `AppContainer` (SharedPreferences `user_settings`). See `doc/UI_FLOW.md` and `doc/DIALOGUE.md`.
 */
data class UserSettings(
    /** Ask follow-up questions (default on). Off: the dialogue asks nothing at all, see `DialogueConfig.followUpsEnabled`. */
    val followUpsEnabled: Boolean = true,
    /** The microphone starts by itself after a question (default on). Only used while [followUpsEnabled]. */
    val handsFreeAnswers: Boolean = true,
    /** Read the filled form on Review and the confirmation on Success aloud (default on). The single source of truth for this switch. */
    val readResultsAloud: Boolean = true,
    val speechRate: SpeechRate = SpeechRate.NORMAL,
    /**
     * "Spoken language" (Gemma speech only): [SPOKEN_AUTO] (default) lets the model identify the language; any other value is a language
     * NAME from `assets/dialogue/languages.json` (stored as written there) and is told to the model (ADR-054).
     */
    val spokenLanguage: String = SPOKEN_AUTO,
    /** Run the offline post-incident analysis after a report is submitted (default on, ADR-055). */
    val postIncidentAnalysis: Boolean = true,
) {
    /** The language to force, or null for auto-detect. */
    val forcedLanguage: String? get() = spokenLanguage.takeIf { it.isNotBlank() && !it.equals(SPOKEN_AUTO, ignoreCase = true) }

    companion object {
        const val SPOKEN_AUTO = "auto"
    }
}

/** Holds [UserSettings] as a `StateFlow`; [onChanged] persists every change. */
class UserSettingsStore(initial: UserSettings = UserSettings(), private val onChanged: (UserSettings) -> Unit = {}) {
    private val _settings = MutableStateFlow(initial)
    val settings: StateFlow<UserSettings> = _settings

    val current: UserSettings get() = _settings.value

    fun update(transform: (UserSettings) -> UserSettings) {
        val next = transform(_settings.value)
        if (next == _settings.value) return
        _settings.value = next
        onChanged(next)
    }
}
