package com.example.test_app.ai.whisper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavPcmTest {
    private fun wav(rate: Int, channels: Int, samples: ShortArray, bits: Int = 16, format: Int = 1, dataSizeOverride: Int? = null, extraChunk: Boolean = false): ByteArray {
        val dataBytes = samples.size * 2
        val extra = if (extraChunk) 8 + 4 else 0
        val bb = ByteBuffer.allocate(44 + extra + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()).putInt(36 + extra + dataBytes).put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()).putInt(16).putShort(format.toShort()).putShort(channels.toShort()).putInt(rate)
            .putInt(rate * channels * bits / 8).putShort((channels * bits / 8).toShort()).putShort(bits.toShort())
        if (extraChunk) bb.put("LIST".toByteArray()).putInt(4).putInt(0x12345678)
        bb.put("data".toByteArray()).putInt(dataSizeOverride ?: dataBytes)
        samples.forEach { bb.putShort(it) }
        return bb.array()
    }

    @Test fun pcm16MonoAt16kScalesToFloat() {
        val out = WavPcm.decode(wav(16_000, 1, shortArrayOf(0, 16384, -32768, 32767)))
        assertEquals(4, out.size)
        assertEquals(0f, out[0], 0f)
        assertEquals(0.5f, out[1], 1e-6f)
        assertEquals(-1f, out[2], 1e-6f)
        assertEquals(32767f / 32768f, out[3], 1e-6f)
    }

    @Test fun stereoIsAveraged() {
        val out = WavPcm.decode(wav(16_000, 2, shortArrayOf(16384, 0, -16384, -16384)))
        assertEquals(2, out.size)
        assertEquals(0.25f, out[0], 1e-6f)
        assertEquals(-0.5f, out[1], 1e-6f)
    }

    @Test fun otherChunksBeforeDataAreSkipped() {
        val out = WavPcm.decode(wav(16_000, 1, shortArrayOf(16384), extraChunk = true))
        assertEquals(listOf(0.5f), out.toList())
    }

    @Test fun streamingHeaderWithZeroDataSizeRunsToEndOfFile() {
        assertEquals(3, WavPcm.decode(wav(16_000, 1, shortArrayOf(1, 2, 3), dataSizeOverride = 0)).size)
        assertEquals(3, WavPcm.decode(wav(16_000, 1, shortArrayOf(1, 2, 3), dataSizeOverride = -1)).size)
    }

    @Test fun downsamplingFrom48kKeepsDurationAndShape() {
        val ramp = ShortArray(48_000) { (it * 20).toShort() } // 1 s ramp, 0..~0.98
        val out = WavPcm.decode(wav(48_000, 1, ramp))
        assertEquals(16_000, out.size)
        assertEquals(ramp[3 * 100] / 32768f, out[100], 1e-4f)
        assertEquals(ramp[3 * 15_999] / 32768f, out[15_999], 1e-4f)
    }

    @Test fun upsamplingFrom8kInterpolatesLinearly() {
        val out = WavPcm.resample(floatArrayOf(0f, 1f, 0f), 8_000, 16_000)
        assertEquals(6, out.size)
        assertEquals(listOf(0f, 0.5f, 1f, 0.5f, 0f, 0f), out.toList())
    }

    @Test fun emptyDataGivesEmptyArray() {
        assertEquals(0, WavPcm.decode(wav(16_000, 1, shortArrayOf())).size)
    }

    @Test fun rejectsNonPcm16AndNonWav() {
        assertThrows(IllegalArgumentException::class.java) { WavPcm.decode(wav(16_000, 1, shortArrayOf(1), format = 3)) }
        assertThrows(IllegalArgumentException::class.java) { WavPcm.decode(wav(16_000, 1, shortArrayOf(1), bits = 8)) }
        assertThrows(IllegalArgumentException::class.java) { WavPcm.decode("hello world, not wav".toByteArray()) }
    }
}
