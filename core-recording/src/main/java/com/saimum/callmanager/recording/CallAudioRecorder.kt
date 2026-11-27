package com.saimum.callmanager.recording

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Captures raw call audio via [AudioRecord] and streams it straight to a
 * [WavFileWriter], tracking running RMS so the caller can tell — after the
 * fact — whether anything real was actually captured.
 *
 * Caller must have already confirmed RECORD_AUDIO is granted; this class
 * doesn't request permissions itself (UI/service layer's job). It still
 * checks for [SecurityException] defensively, though — permission can be
 * revoked by the user at any time after being granted, independent of
 * whatever the UI last confirmed.
 *
 * **Why recordings can come back saved-but-silent even when a source
 * "started" cleanly:** on Android 10+, [AudioSourceCandidate.VOICE_COMMUNICATION]
 * is required by the platform to run with an Acoustic Echo Canceler (AEC)
 * on its capture path (see source.android.com/devices/audio/implement-pre-processing).
 * AEC's whole job is to strip the played-back/speaker signal back out of
 * whatever the mic picks up — which is exactly the signal
 * [RecordingSettings.forceSpeakerphoneWhileRecording]'s speaker-leak trick
 * depends on. Left enabled, the AEC (and, on some OEMs, the paired Noise
 * Suppressor) can cancel that leaked call audio right back out, so the
 * file saves fine but ends up empty/near-silent. [disablePreprocessingEffects]
 * turns AEC/NS/AGC off on our own capture session — never on the call
 * itself — right after the session exists, the same technique WebRTC's own
 * Android audio layer and several open-source call recorders use.
 */
class CallAudioRecorder(private val sampleRateHz: Int = SAMPLE_RATE_HZ) {

    sealed class StartResult {
        data class Started(val source: AudioSourceCandidate) : StartResult()
        /** No source initialized/started for reasons unrelated to permission — a real device/OS limitation. */
        data object AllSourcesFailed : StartResult()
        /**
         * At least one candidate failed specifically because RECORD_AUDIO
         * isn't granted right now. Kept distinct from [AllSourcesFailed] so
         * the caller never mis-attributes "permission got revoked" as "this
         * device can't record calls" — those need very different handling.
         */
        data object PermissionDenied : StartResult()
    }

    private sealed class Creation {
        data class Created(val audioRecord: AudioRecord) : Creation()
        data object PermissionDenied : Creation()
        data object Unsupported : Creation()
    }

    private var audioRecord: AudioRecord? = null
    private var wavWriter: WavFileWriter? = null
    private var recordingJob: Job? = null
    private var runningRmsSum = 0.0
    private var runningRmsCount = 0
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null

    val isRecording: Boolean get() = audioRecord != null

    fun start(
        outputFile: File,
        scope: CoroutineScope,
        candidates: List<AudioSourceCandidate> = AudioSourceCandidate.inTryOrder
    ): StartResult {
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize <= 0) return StartResult.AllSourcesFailed

        var sawPermissionDenied = false

        for (candidate in candidates) {
            val creation = createAudioRecord(candidate, minBufferSize)

            val record = when (creation) {
                is Creation.Created -> creation.audioRecord
                Creation.PermissionDenied -> {
                    sawPermissionDenied = true
                    continue
                }
                Creation.Unsupported -> continue
            }

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                continue
            }

            val writer = WavFileWriter(outputFile, sampleRateHz)
            try {
                writer.open()
            } catch (e: IOException) {
                // Can't create/write the output file (storage full or
                // unavailable). Release the AudioRecord we just created —
                // otherwise it leaks and blocks the mic for later attempts.
                Log.e(TAG, "Couldn't open the output file for recording", e)
                record.release()
                return StartResult.AllSourcesFailed
            }

            val startedOk = try {
                record.startRecording()
                record.recordingState == AudioRecord.RECORDSTATE_RECORDING
            } catch (e: IllegalStateException) {
                false
            } catch (e: SecurityException) {
                sawPermissionDenied = true
                false
            }

            if (!startedOk) {
                writer.close()
                record.release()
                continue
            }

            audioRecord = record
            wavWriter = writer
            runningRmsSum = 0.0
            runningRmsCount = 0
            disablePreprocessingEffects(record.audioSessionId)

            recordingJob = scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(minBufferSize * 2)
                var consecutiveReadFailures = 0
                var bytesSinceHeaderFlush = 0L
                try {
                    while (isActive) {
                        val read = record.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            consecutiveReadFailures = 0
                            writer.writePcmChunk(buffer, read)
                            runningRmsSum += PcmAmplitudeAnalyzer.rms(buffer, read)
                            runningRmsCount++

                            // Keep the on-disk header current so a crash mid-call
                            // still leaves a playable file (see WavFileWriter.flushHeader).
                            bytesSinceHeaderFlush += read
                            if (bytesSinceHeaderFlush >= HEADER_FLUSH_INTERVAL_BYTES) {
                                writer.flushHeader()
                                bytesSinceHeaderFlush = 0
                            }
                        } else {
                            // read() returned 0 or an error code (dead object,
                            // invalid operation…). Looping straight back would
                            // spin the CPU at 100% for the rest of the call, so
                            // back off, and give up on a capture that's clearly
                            // gone — what was already written is still saved
                            // when the call ends and stop() runs.
                            consecutiveReadFailures++
                            if (consecutiveReadFailures >= MAX_CONSECUTIVE_READ_FAILURES) {
                                Log.e(TAG, "AudioRecord.read kept failing (last code $read); ending capture early")
                                break
                            }
                            delay(READ_RETRY_DELAY_MILLIS)
                        }
                    }
                } catch (e: IOException) {
                    // Disk full / storage error mid-call — stop capturing but
                    // don't crash; stop() still finalizes whatever was written.
                    Log.e(TAG, "I/O error while writing recording; ending capture early", e)
                }
            }

            return StartResult.Started(candidate)
        }

        return if (sawPermissionDenied) StartResult.PermissionDenied else StartResult.AllSourcesFailed
    }

    /** Stops recording and returns the average RMS across the whole capture (0.0 if nothing was captured). */
    suspend fun stop(): Double {
        recordingJob?.cancelAndJoin()
        recordingJob = null

        releasePreprocessingEffects()

        audioRecord?.let { record ->
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            } catch (e: IllegalStateException) {
                // already stopped — nothing to do
            }
            record.release()
        }
        audioRecord = null

        wavWriter?.close()
        wavWriter = null

        return if (runningRmsCount > 0) runningRmsSum / runningRmsCount else 0.0
    }

    /**
     * Disables Acoustic Echo Cancellation, Noise Suppression, and Automatic
     * Gain Control on our own capture session (identified by [sessionId]).
     *
     * These effects are attached automatically by the platform for some
     * [AudioSourceCandidate]s (VOICE_COMMUNICATION is required by Android to
     * ship with AEC on Android 10+) and, on some OEMs, get attached to
     * VOICE_RECOGNITION too even though the source is meant to be raw. Left
     * on, AEC in particular will actively cancel the very speaker-leaked
     * call audio the forced-speakerphone fallback depends on, producing a
     * file that saves fine but is empty/near-silent — indistinguishable at
     * a glance from a genuinely unsupported device. Not every device
     * exposes these effects, so every step here is defensive: absence or a
     * failure to create/enable one is not an error, just a no-op.
     */
    private fun disablePreprocessingEffects(sessionId: Int) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = false }
            }
        } catch (e: RuntimeException) {
            // Some OEM audio HALs throw here instead of returning null — never fatal to recording.
        }
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = false }
            }
        } catch (e: RuntimeException) {
            // Same defensive handling as above.
        }
        try {
            if (AutomaticGainControl.isAvailable()) {
                automaticGainControl = AutomaticGainControl.create(sessionId)?.apply { enabled = false }
            }
        } catch (e: RuntimeException) {
            // Same defensive handling as above.
        }
    }

    private fun releasePreprocessingEffects() {
        echoCanceler?.release()
        echoCanceler = null
        noiseSuppressor?.release()
        noiseSuppressor = null
        automaticGainControl?.release()
        automaticGainControl = null
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(candidate: AudioSourceCandidate, minBufferSize: Int): Creation {
        return try {
            Creation.Created(
                AudioRecord(
                    candidate.mediaRecorderConstant,
                    sampleRateHz,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBufferSize * 2
                )
            )
        } catch (e: SecurityException) {
            Creation.PermissionDenied
        } catch (e: IllegalArgumentException) {
            Creation.Unsupported // unsupported parameters for this source on this hardware
        }
    }

    companion object {
        // Telephony voice band fits comfortably in 16 kHz mono — keeps
        // files small (matches "lightweight") without losing call quality.
        const val SAMPLE_RATE_HZ = 16000

        private const val TAG = "CallAudioRecorder"
        private const val MAX_CONSECUTIVE_READ_FAILURES = 50
        private const val READ_RETRY_DELAY_MILLIS = 20L

        // 16 kHz mono PCM16 = 32,000 bytes/s, so this is roughly every 10 seconds.
        private const val HEADER_FLUSH_INTERVAL_BYTES = 320_000L
    }
}
