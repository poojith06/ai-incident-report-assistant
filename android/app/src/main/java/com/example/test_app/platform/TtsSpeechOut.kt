package com.example.test_app.platform

import android.util.Log
import com.example.test_app.dialogue.SpeechOut
import com.example.test_app.domain.service.TextToSpeechService
import com.example.test_app.domain.service.TtsUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * [SpeechOut] over the app's [TextToSpeechService]. A TTS failure is non-blocking (ADR-012): [onUnavailable] shows the
 * "voice read-out unavailable" banner and the dialogue continues. A short pause after each utterance keeps the
 * microphone from recording the tail of our own voice. [speaking] drives the on-screen "reading the question" state.
 * See `doc/DIALOGUE.md`.
 */
class TtsSpeechOut(
    private val tts: TextToSpeechService,
    private val onUnavailable: () -> Unit,
    private val pauseMs: Long = 300,
) : SpeechOut {
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking

    @Volatile private var speakJob: Job? = null

    override suspend fun speak(text: String, language: String) {
        _speaking.value = true
        val t0 = System.currentTimeMillis()
        try {
            coroutineScope {
                val job = launch { tts.speak(text, language) }
                speakJob = job
                job.join()
            }
            Log.i(TAG, "spoke (${tts.javaClass.simpleName}) lang=$language ms=${System.currentTimeMillis() - t0} text=\"${text.take(50)}\"")
        } catch (e: TtsUnavailableException) {
            Log.w(TAG, "speech unavailable: ${e.message}", e)
            onUnavailable()
        } catch (e: CancellationException) {
            Log.i(TAG, "speech cancelled after ${System.currentTimeMillis() - t0} ms: \"${text.take(50)}\"")
            throw e
        } catch (e: Exception) {
            // not "unavailable" but still a failure the user would hear as silence: never swallow it quietly
            Log.w(TAG, "speech failed (${e.javaClass.simpleName}: ${e.message}) for \"${text.take(50)}\"", e)
            onUnavailable()
        } finally {
            speakJob = null
            _speaking.value = false
        }
        delay(pauseMs)
    }

    /** Cuts the current utterance short (the user tapped the mic); [speak] then returns and the dialogue listens. */
    fun skip() {
        speakJob?.cancel()
        tts.stop()
    }

    private companion object {
        const val TAG = "TtsSpeechOut"
    }
}
