package com.example.test_app.ai.tts

/**
 * Buffer sizing of the streaming `AudioTrack` (pure, unit-tested). Measured on the QIDK (Android 15, `AudioTrackProbeTest`): a
 * MODE_STREAM track does not START playing before its buffer is full unless a start threshold is set, so a 30 s buffer with a 3 s
 * sentence in it stayed silent forever (playbackHeadPosition 0) and a 1 s buffer never started for a line shorter than 1 s.
 * API 31+ has `setStartThresholdInFrames`: a long buffer (the next sentence is synthesized while this one plays) with a short threshold.
 * API 30 has no threshold: a buffer of one second and silence written at the end of the utterance to fill it.
 */
object PcmBufferPolicy {
    const val LONG_BUFFER_SECONDS = 30
    const val SHORT_BUFFER_SECONDS = 1

    /** Start playing after this much audio (ms) when a threshold can be set. */
    const val START_THRESHOLD_MS = 100

    data class Plan(
        /** Buffer capacity in frames. */
        val bufferFrames: Int,
        /** `startThresholdInFrames` to set, or null when the API cannot (the buffer must then be filled to start). */
        val startThresholdFrames: Int?,
    )

    fun plan(sdkInt: Int, sampleRate: Int): Plan =
        if (sdkInt >= 31) Plan(sampleRate * LONG_BUFFER_SECONDS, sampleRate * START_THRESHOLD_MS / 1000)
        else Plan(sampleRate * SHORT_BUFFER_SECONDS, null)

    /**
     * Frames of silence to append at the end of an utterance so that a track without a start threshold starts: the buffer minus what was
     * written, 0 when a threshold is used or enough was written (the track has started by itself).
     */
    fun padFrames(plan: Plan, framesWritten: Long): Int =
        if (plan.startThresholdFrames != null || framesWritten >= plan.bufferFrames) 0 else (plan.bufferFrames - framesWritten).toInt()
}
