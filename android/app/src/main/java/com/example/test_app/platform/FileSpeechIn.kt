package com.example.test_app.platform

import com.example.test_app.dialogue.AsrResult
import com.example.test_app.dialogue.FieldAwareSpeechIn
import com.example.test_app.domain.service.AudioInput
import com.example.test_app.domain.service.SpeechToTextException
import com.example.test_app.domain.service.SpeechToTextService
import com.example.test_app.domain.service.SttHint
import com.example.test_app.eval.WavInfo
import java.io.File

/**
 * [com.example.test_app.dialogue.SpeechIn] that reads a WAV file instead of the microphone (Level 4 tests,
 * `doc/TESTING.md`), so the whole pipeline (speech-to-text, extraction, dialogue, form) runs on recorded or synthetic
 * audio without anyone speaking. [fileFor] maps the dialogue field the next utterance answers (`null` = narration,
 * `safety`, `readback`, or a field key; set through [expectAnswerFor]) to its WAV file.
 *
 * `listen` returns null when there is no file for that key or the file is absent (= silence / "no response", exactly
 * like the microphone); it ignores the silence and length limits. The file must be 16 kHz mono 16-bit PCM
 * (a [SpeechToTextException] otherwise). The audio goes to the injected [SpeechToTextService]; the field key is passed as
 * [SttHint] like `MicSpeechIn` does (real recognisers ignore it, the mock STT uses it).
 * Pure JVM code (no Android classes), so it is unit-tested.
 */
class FileSpeechIn(
    private val stt: SpeechToTextService,
    private val fileFor: (field: String?) -> File?,
) : FieldAwareSpeechIn {
    @Volatile private var expected: String? = null

    override fun expectAnswerFor(field: String?) {
        expected = field
    }

    override suspend fun listen(silenceTimeoutSec: Int, maxSeconds: Int): AsrResult? {
        val key = expected
        val file = fileFor(key)?.takeIf { it.isFile } ?: return null
        val info = try {
            WavInfo.read(file)
        } catch (e: IllegalArgumentException) {
            throw SpeechToTextException(e.message ?: "Unreadable WAV file ${file.name}", e)
        }
        if (!info.isRecognizerFormat) throw SpeechToTextException("${file.name}: expected 16 kHz mono 16-bit PCM but found $info")
        val t = stt.transcribe(AudioInput(file, info.durationMs), SttHint(key))
        return if (t.text.isBlank()) null else AsrResult(t.text, t.confidence, t.language)
    }
}
