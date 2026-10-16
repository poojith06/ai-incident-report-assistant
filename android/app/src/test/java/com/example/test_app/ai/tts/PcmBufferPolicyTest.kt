package com.example.test_app.ai.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The AudioTrack start rule that silenced every spoken line (a stream track plays only once its buffer is full unless a start threshold is set). */
class PcmBufferPolicyTest {
    @Test fun newAndroidGetsALongBufferWithAShortStartThreshold() {
        val p = PcmBufferPolicy.plan(35, 22050)
        assertEquals(22050 * 30, p.bufferFrames)
        assertNotNull(p.startThresholdFrames)
        assertEquals(2205, p.startThresholdFrames)
        assertEquals("a threshold means no padding, whatever was written", 0, PcmBufferPolicy.padFrames(p, 100))
    }

    @Test fun theThresholdIsAlwaysFarBelowTheBufferSoAShortLineStarts() {
        for (rate in listOf(16000, 22050, 24000, 44100)) for (sdk in listOf(31, 34, 36)) {
            val p = PcmBufferPolicy.plan(sdk, rate)
            assertEquals("sdk $sdk rate $rate", true, p.startThresholdFrames!! in 1 until rate)
            assertEquals(true, p.startThresholdFrames!! < p.bufferFrames / 10)
        }
    }

    @Test fun api30CannotSetAThresholdSoItUsesOneSecondAndPadsTheEndWithSilence() {
        val p = PcmBufferPolicy.plan(30, 24000)
        assertNull(p.startThresholdFrames)
        assertEquals(24000, p.bufferFrames)
        assertEquals("a 0.5 s line is padded to a full buffer", 12000, PcmBufferPolicy.padFrames(p, 12000))
        assertEquals(24000 - 100, PcmBufferPolicy.padFrames(p, 100))
        assertEquals("a line of a full buffer or more has started by itself", 0, PcmBufferPolicy.padFrames(p, 24000))
        assertEquals(0, PcmBufferPolicy.padFrames(p, 90000))
    }
}
