package com.saimum.callmanager.recording

import kotlin.math.sqrt

/**
 * Detects whether captured PCM16 audio is effectively silent.
 *
 * This is the honesty check behind [RecordingCapability]: a source can
 * "successfully" initialize and record on many OEMs while the OS quietly
 * routes zero real audio into it. Without this check we'd report a
 * successful recording that's actually an empty file — exactly the fake
 * success state we're required to avoid.
 */
object PcmAmplitudeAnalyzer {

    /** Below this RMS (out of a max possible ~32767 for 16-bit PCM), audio counts as silence. */
    private const val SILENCE_RMS_THRESHOLD = 40.0

    /**
     * Computes RMS amplitude for a chunk of little-endian PCM16 mono samples.
     * [length] is the number of valid bytes in [buffer] (must be even).
     */
    fun rms(buffer: ByteArray, length: Int): Double {
        if (length < 2) return 0.0
        var sumSquares = 0.0
        var sampleCount = 0
        var i = 0
        while (i + 1 < length) {
            val low = buffer[i].toInt() and 0xff
            val high = buffer[i + 1].toInt()
            val sample = (high shl 8) or low
            sumSquares += (sample.toDouble() * sample.toDouble())
            sampleCount++
            i += 2
        }
        if (sampleCount == 0) return 0.0
        return sqrt(sumSquares / sampleCount)
    }

    fun isSilent(averageRms: Double): Boolean = averageRms < SILENCE_RMS_THRESHOLD
}
