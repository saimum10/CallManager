package com.saimum.callmanager.recording

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord

/**
 * Runs the call-independent half of capability detection: does any
 * candidate [AudioSourceCandidate] even initialize on this hardware?
 *
 * This can only prove a negative (INIT_UNSUPPORTED) with confidence. A
 * positive here just means "worth trying on a real call" — see
 * [RecordingCapability] for why real confirmation needs an actual call.
 *
 * Caller must have RECORD_AUDIO granted before calling this.
 */
class RecordingCapabilityProber {

    @SuppressLint("MissingPermission")
    fun probeInitOnly(): RecordingCapability {
        val minBufferSize = AudioRecord.getMinBufferSize(
            CallAudioRecorder.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize <= 0) return RecordingCapability.INIT_UNSUPPORTED

        for (candidate in AudioSourceCandidate.inTryOrder) {
            // Deliberately catches RuntimeException broadly, not just the
            // two documented exception types: this runs synchronously on
            // the calling (UI) thread with no coroutine boundary to fall
            // back on, so an unanticipated throw from some OEM's audio HAL
            // must not be allowed to crash the app outright.
            val record = try {
                AudioRecord(
                    candidate.mediaRecorderConstant,
                    CallAudioRecorder.SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBufferSize * 2
                )
            } catch (e: RuntimeException) {
                null
            }

            val initialized = record?.state == AudioRecord.STATE_INITIALIZED
            record?.release()

            // Initialized here means "worth trying" — not yet a confirmed
            // capability. Real confirmation only comes from analyzing a
            // recording made during an actual call (see CallRecordingService).
            if (initialized) return RecordingCapability.UNKNOWN
        }

        return RecordingCapability.INIT_UNSUPPORTED
    }
}
