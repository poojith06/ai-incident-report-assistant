package com.example.test_app.platform

import com.example.test_app.dialogue.AsrResult
import com.example.test_app.dialogue.FieldAwareSpeechIn
import com.example.test_app.domain.service.SpeechToTextService
import com.example.test_app.domain.service.SttHint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** What the microphone side of the dialogue is doing right now (drives the Home / dialogue screens). */
enum class MicPhase { IDLE, LISTENING, TRANSCRIBING }

/**
 * Real [com.example.test_app.dialogue.SpeechIn]: opens the microphone ([AudioRecorder]), waits for speech with a
 * simple energy-based voice activity detector (level thresholds), stops 2 s after speech ends (or at `maxSeconds`),
 * then runs the [SpeechToTextService]. Returns null when nobody spoke within `silenceTimeoutSec` (= "no response";
 * the dialogue never retries). [stopNow] (the stop button) ends the recording and transcribes it even if no speech was
 * detected, so the silent emulator microphone still works with the mock STT. The STT hint is the dialogue field set by
 * [expectAnswerFor]; real recognisers ignore it. See `doc/DIALOGUE.md`.
 *
 * Throws what [AudioRecorder.start] / the STT throw (`IllegalStateException`, `SpeechToTextException`); the dialogue
 * treats that as an error for the first narration and as "no response" for answers.
 */
class MicSpeechIn(
    private val recorder: AudioRecorder,
    private val stt: SpeechToTextService,
    private val scope: CoroutineScope,
    private val minRecordingMs: Long = 1000,
) : FieldAwareSpeechIn {
    private val _phase = MutableStateFlow(MicPhase.IDLE)

    /** IDLE between utterances, LISTENING while recording, TRANSCRIBING while the STT runs. */
    val phase: StateFlow<MicPhase> = _phase

    @Volatile private var expected: String? = null
    @Volatile private var stopRequested = false

    override fun expectAnswerFor(field: String?) {
        expected = field
    }

    /** The user pressed stop: end the current recording now and transcribe whatever was captured. */
    fun stopNow() {
        stopRequested = true
    }

    override suspend fun listen(silenceTimeoutSec: Int, maxSeconds: Int): AsrResult? {
        stopRequested = false
        recorder.start(scope)
        _phase.value = MicPhase.LISTENING
        val startedAt = System.currentTimeMillis()
        var heardSpeech = false
        var silentSince = 0L
        try {
            while (true) {
                delay(POLL_MS)
                if (stopRequested) break
                val now = System.currentTimeMillis()
                val elapsed = now - startedAt
                val level = recorder.level.value
                if (level >= SPEECH_LEVEL) {
                    heardSpeech = true
                    silentSince = 0L
                } else if (heardSpeech && level < SILENCE_LEVEL) {
                    if (silentSince == 0L) silentSince = now
                    if (now - silentSince >= SILENCE_STOP_MS) break
                }
                if (!heardSpeech && elapsed >= silenceTimeoutSec * 1000L) break
                if (elapsed >= maxSeconds * 1000L) break
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { recorder.cancel() }
            _phase.value = MicPhase.IDLE
            throw e
        }
        val userStopped = stopRequested
        val audio = recorder.stop()
        if (audio == null || (!heardSpeech && !userStopped) || audio.durationMs < minRecordingMs) {
            audio?.file?.delete()
            _phase.value = MicPhase.IDLE
            return null
        }
        _phase.value = MicPhase.TRANSCRIBING
        try {
            val t = stt.transcribe(audio, SttHint(expected))
            return if (t.text.isBlank()) null else AsrResult(t.text, t.confidence, t.language)
        } finally {
            audio.file.delete()
            _phase.value = MicPhase.IDLE
        }
    }

    private companion object {
        const val POLL_MS = 100L
        const val SPEECH_LEVEL = 0.10f
        const val SILENCE_LEVEL = 0.06f
        const val SILENCE_STOP_MS = 2000L
    }
}
