package com.example.test_app.platform

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.test_app.domain.service.TextToSpeechService
import com.example.test_app.domain.service.TtsUnavailableException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [TextToSpeechService] backed by Android's on-device [TextToSpeech] (default engine, offline voices only). Engine is
 * initialised lazily. English locale (en-IN / en-US / en-GB variants fall back to any English); among the engine's voices
 * [TtsVoicePicker] prefers one that is installed and does not need the network; if the engine only offers network
 * voices it is treated as unavailable (banner, flow continues). Speech rate is [TtsConfig.SPEECH_RATE]. The engine package and
 * the chosen voice are logged once (tag `TtsService`). Check on a device: `doc/DEVICE_SETUP.md`.
 * Swap for a model-based TTS per `doc/INTEGRATION_GUIDE.md`.
 */
class AndroidTextToSpeechService(private val context: Context) : TextToSpeechService {
    private var tts: TextToSpeech? = null
    private val waiting = ConcurrentHashMap<String, CancellableContinuation<Unit>>()
    @Volatile private var rate = TtsConfig.SPEECH_RATE
    private val params = Bundle().apply { putBoolean(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS, false) }

    private suspend fun engine(): TextToSpeech {
        tts?.let { return it }
        return suspendCancellableCoroutine { cont ->
            var created: TextToSpeech? = null
            created = TextToSpeech(context.applicationContext) { status ->
                val engine = created
                if (status != TextToSpeech.SUCCESS || engine == null) {
                    created?.shutdown()
                    if (cont.isActive) cont.resumeWithException(TtsUnavailableException("Text-to-speech failed to start."))
                    return@TextToSpeech
                }
                val problem = configureOffline(engine)
                if (problem != null) {
                    Log.w(TAG, "TTS unavailable: $problem")
                    engine.shutdown()
                    if (cont.isActive) cont.resumeWithException(TtsUnavailableException(problem))
                    return@TextToSpeech
                }
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) {
                        waiting.remove(utteranceId)?.takeIf { it.isActive }?.resume(Unit)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        waiting.remove(utteranceId)?.takeIf { it.isActive }
                            ?.resumeWithException(TtsUnavailableException("Speech output failed."))
                    }
                })
                tts = engine
                if (cont.isActive) cont.resume(engine)
            }
            cont.invokeOnCancellation { created?.shutdown() }
        }
    }

    /** Language, voice and rate for offline English. Returns a problem description, or null when the engine is usable. */
    private fun configureOffline(engine: TextToSpeech): String? {
        var lang = engine.setLanguage(Locale.getDefault().takeIf { it.language == "en" } ?: Locale.ENGLISH)
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) lang = engine.setLanguage(Locale.US)
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            return "The text-to-speech engine has no English voice installed."
        }
        engine.setSpeechRate(rate)
        val voices = runCatching { engine.voices }.getOrNull().orEmpty()
        val candidates = voices.map {
            TtsVoicePicker.Candidate(
                name = it.name, language = it.locale.language, country = it.locale.country,
                networkRequired = it.isNetworkConnectionRequired ||
                    it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS) ||
                    it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NETWORK_RETRIES_COUNT),
                notInstalled = it.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED),
            )
        }
        val engineName = engine.defaultEngine
        return when (val pick = TtsVoicePicker.pick(candidates, Locale.getDefault().country)) {
            is TtsVoicePicker.Pick.Voice -> {
                val voice = voices.first { it.name == pick.name }
                engine.voice = voice
                Log.i(TAG, "engine=$engineName voice=${voice.name} locale=${voice.locale} offline=true rate=${TtsConfig.SPEECH_RATE}")
                null
            }
            // Some engines list no voices at all: the language was accepted above, so go on with the engine default.
            TtsVoicePicker.Pick.NoVoiceList -> {
                Log.i(TAG, "engine=$engineName voice=<engine default, no voice list> rate=${TtsConfig.SPEECH_RATE}")
                null
            }
            TtsVoicePicker.Pick.OnlyNetwork ->
                "The text-to-speech engine only offers online voices, and the app works offline."
        }
    }

    override suspend fun speak(text: String) {
        // Timeouts surface as TimeoutCancellationException (a CancellationException): it must become
        // TtsUnavailableException or the caller's coroutine would be cancelled silently and the flow would hang.
        try {
            val engine = withTimeout(INIT_TIMEOUT_MS) { engine() }
            engine.setSpeechRate(rate) // the user setting applies from the next utterance
            val id = UUID.randomUUID().toString()
            withTimeout(SPEAK_TIMEOUT_MS) {
                suspendCancellableCoroutine<Unit> { cont ->
                    waiting[id] = cont
                    cont.invokeOnCancellation { waiting.remove(id); engine.stop() }
                    if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, id) != TextToSpeech.SUCCESS) {
                        waiting.remove(id)
                        cont.resumeWithException(TtsUnavailableException("Speech output failed."))
                    }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw TtsUnavailableException("Speech output timed out.", e)
        }
    }

    override fun setSpeechRate(rate: Float) {
        this.rate = rate.coerceIn(TtsConfig.MIN_RATE, TtsConfig.MAX_RATE)
    }

    override fun stop() {
        tts?.stop()
    }

    override fun shutdown() {
        tts?.shutdown()
        tts = null
    }

    private companion object {
        const val TAG = "TtsService"
        const val INIT_TIMEOUT_MS = 8_000L
        const val SPEAK_TIMEOUT_MS = 30_000L
    }
}
