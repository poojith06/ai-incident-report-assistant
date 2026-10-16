package com.example.test_app.platform

/** Text-to-speech settings in one place (see `doc/DEVICE_SETUP.md` for the engine checks). */
object TtsConfig {
    /** Speech rate passed to the engine; 1.0 = normal. */
    const val SPEECH_RATE = 1.0f

    /** Bounds of the rate the settings screen may ask for. */
    const val MIN_RATE = 0.5f
    const val MAX_RATE = 2.0f
}

/**
 * Chooses an offline English voice from the engine's voice list (pure Kotlin, unit-tested). Order: installed, no network
 * needed, English; then the device country (en-IN on an Indian phone), then en-US, en-GB, any other English.
 */
object TtsVoicePicker {
    data class Candidate(
        val name: String,
        val language: String,
        val country: String,
        val networkRequired: Boolean,
        val notInstalled: Boolean,
    )

    sealed interface Pick {
        data class Voice(val name: String) : Pick

        /** The engine lists no English voice at all (it may still speak with its default; the caller decides). */
        data object NoVoiceList : Pick

        /** English voices exist but every one needs the network (or is not installed). */
        data object OnlyNetwork : Pick
    }

    fun pick(voices: List<Candidate>, deviceCountry: String): Pick {
        val english = voices.filter { it.language.equals("en", ignoreCase = true) }
        if (english.isEmpty()) return Pick.NoVoiceList
        val offline = english.filter { !it.networkRequired && !it.notInstalled }
        if (offline.isEmpty()) return Pick.OnlyNetwork
        val order = listOf(deviceCountry, "US", "GB")
        val best = order.firstNotNullOfOrNull { c -> offline.firstOrNull { it.country.equals(c, ignoreCase = true) } } ?: offline.first()
        return Pick.Voice(best.name)
    }
}
