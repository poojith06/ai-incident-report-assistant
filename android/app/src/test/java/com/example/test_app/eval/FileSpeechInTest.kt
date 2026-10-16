package com.example.test_app.eval

import com.example.test_app.domain.service.AudioInput
import com.example.test_app.domain.service.SpeechToTextException
import com.example.test_app.domain.service.SpeechToTextService
import com.example.test_app.domain.service.SttHint
import com.example.test_app.domain.service.Transcript
import com.example.test_app.platform.FileSpeechIn
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Writes a canonical PCM WAV (44-byte header) with silence as data. */
internal fun wavBytes(
    sampleRate: Int = 16_000,
    channels: Int = 1,
    bits: Int = 16,
    seconds: Double = 1.0,
    extraChunk: ByteArray? = null,
    dataSizeOverride: Long? = null,
    riff: String = "RIFF",
): ByteArray {
    val dataBytes = (sampleRate * channels * (bits / 8) * seconds).toInt()
    fun le32(v: Long) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).array()
    fun le16(v: Int) = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
    val out = ByteArrayOutputStream()
    out.write(riff.toByteArray(Charsets.US_ASCII))
    out.write(le32(36L + dataBytes + (extraChunk?.let { 8L + it.size + (it.size and 1) } ?: 0L)))
    out.write("WAVE".toByteArray())
    out.write("fmt ".toByteArray())
    out.write(le32(16))
    out.write(le16(1))
    out.write(le16(channels))
    out.write(le32(sampleRate.toLong()))
    out.write(le32((sampleRate * channels * (bits / 8)).toLong()))
    out.write(le16(channels * (bits / 8)))
    out.write(le16(bits))
    if (extraChunk != null) {
        out.write("LIST".toByteArray())
        out.write(le32(extraChunk.size.toLong()))
        out.write(extraChunk)
        if (extraChunk.size % 2 == 1) out.write(0)
    }
    out.write("data".toByteArray())
    out.write(le32(dataSizeOverride ?: dataBytes.toLong()))
    out.write(ByteArray(dataBytes))
    return out.toByteArray()
}

class WavInfoTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(bytes: ByteArray) = tmp.newFile().also { it.writeBytes(bytes) }

    @Test fun readsTheRecognizerFormat() {
        val info = WavInfo.read(file(wavBytes(seconds = 1.5)))
        assertEquals(16_000, info.sampleRate)
        assertEquals(1, info.channels)
        assertEquals(16, info.bitsPerSample)
        assertEquals(48_000L, info.dataBytes)
        assertEquals(1500L, info.durationMs)
        assertTrue(info.isRecognizerFormat)
    }

    @Test fun otherFormatsAreDescribedButNotRecognizerFormat() {
        val info = WavInfo.read(file(wavBytes(sampleRate = 8_000, channels = 2, seconds = 1.0)))
        assertEquals(1000L, info.durationMs)
        assertFalse(info.isRecognizerFormat)
        assertEquals("8000 Hz, 2 channel(s), 16 bit", info.toString())
        assertFalse(WavInfo.read(file(wavBytes(bits = 8))).isRecognizerFormat)
        assertFalse(WavInfo.read(file(wavBytes(sampleRate = 44_100))).isRecognizerFormat)
    }

    @Test fun skipsOtherChunksIncludingOddSizedOnes() {
        assertEquals(1000L, WavInfo.read(file(wavBytes(extraChunk = ByteArray(10)))).durationMs)
        assertEquals(1000L, WavInfo.read(file(wavBytes(extraChunk = ByteArray(7)))).durationMs)
    }

    @Test fun aStreamingHeaderWithoutDataSizeRunsToTheEndOfTheFile() {
        assertEquals(500L, WavInfo.read(file(wavBytes(seconds = 0.5, dataSizeOverride = 0))).durationMs)
        assertEquals(500L, WavInfo.read(file(wavBytes(seconds = 0.5, dataSizeOverride = 0xFFFFFFFFL))).durationMs)
    }

    @Test fun rejectsFilesThatAreNotWav() {
        for (bytes in listOf(ByteArray(0), "hello world, this is not audio".toByteArray(), wavBytes(riff = "RIFX"))) {
            try {
                WavInfo.read(file(bytes))
                fail("expected IllegalArgumentException")
            } catch (e: IllegalArgumentException) {
                assertNotNull(e.message)
            }
        }
    }
}

class FileSpeechInTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeStt(private val text: String = "water leak", private val confidence: Float = 0.8f) : SpeechToTextService {
        val calls = mutableListOf<Pair<AudioInput, SttHint>>()
        override suspend fun transcribe(audio: AudioInput, hint: SttHint): Transcript {
            calls += audio to hint
            return Transcript(text, "en", confidence)
        }
    }

    private fun wav(name: String, bytes: ByteArray = wavBytes(seconds = 2.0)): File = File(tmp.root, name).also { it.writeBytes(bytes) }

    @Test fun transcribesTheFileForTheExpectedField() = runTest {
        val narration = wav("narration.wav")
        val answer = wav("answer.wav", wavBytes(seconds = 0.5))
        val stt = FakeStt()
        val inp = FileSpeechIn(stt) { key -> if (key == null) narration else if (key == "injury") answer else null }

        inp.expectAnswerFor(null)
        val r = inp.listen()
        assertEquals("water leak", r!!.text)
        assertEquals(0.8f, r.confidence)
        assertEquals("en", r.language)
        inp.expectAnswerFor("injury")
        assertNotNull(inp.listen())

        assertEquals(2, stt.calls.size)
        assertEquals(narration, stt.calls[0].first.file)
        assertEquals(2000L, stt.calls[0].first.durationMs)
        assertNull(stt.calls[0].second.expectedField)
        assertEquals(answer, stt.calls[1].first.file)
        assertEquals(500L, stt.calls[1].first.durationMs)
        assertEquals("injury", stt.calls[1].second.expectedField)
    }

    @Test fun returnsNullWithoutAFileAndNeverCallsTheRecogniser() = runTest {
        val stt = FakeStt()
        val inp = FileSpeechIn(stt) { key -> if (key == "location") File(tmp.root, "does_not_exist.wav") else null }
        inp.expectAnswerFor("injury") // no file mapped
        assertNull(inp.listen())
        inp.expectAnswerFor("location") // mapped to an absent file
        assertNull(inp.listen())
        assertTrue(stt.calls.isEmpty())
    }

    @Test fun blankTranscriptIsSilence() = runTest {
        val f = wav("a.wav")
        val inp = FileSpeechIn(FakeStt(text = "  ")) { f }
        assertNull(inp.listen())
    }

    @Test fun wrongAudioFormatIsRejectedBeforeRecognition() = runTest {
        val stt = FakeStt()
        val f = wav("slow.wav", wavBytes(sampleRate = 8_000))
        val inp = FileSpeechIn(stt) { f }
        try {
            inp.listen()
            fail("expected SpeechToTextException")
        } catch (e: SpeechToTextException) {
            assertTrue(e.message, e.message!!.contains("16 kHz mono 16-bit PCM"))
        }
        assertTrue(stt.calls.isEmpty())
    }

    @Test fun unreadableFileIsASpeechToTextException() = runTest {
        val f = wav("junk.wav", "not a wav".toByteArray())
        val inp = FileSpeechIn(FakeStt()) { f }
        try {
            inp.listen()
            fail("expected SpeechToTextException")
        } catch (e: SpeechToTextException) {
            assertNotNull(e.message)
        }
    }
}
