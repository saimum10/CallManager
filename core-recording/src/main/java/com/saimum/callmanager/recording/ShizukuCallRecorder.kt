package com.saimum.callmanager.recording

import android.util.Log
import com.saimum.callmanager.recording.shizuku.IAudioSink
import com.saimum.callmanager.recording.shizuku.ShizukuBridge
import java.io.File
import java.io.IOException
import kotlinx.coroutines.delay

/**
 * Same job as [CallAudioRecorder], but the audio is captured by the Shizuku
 * user service (shell user, which may use the real VOICE_CALL source) and
 * streamed to us over Binder. We write it to the WAV file and track RMS so the
 * "was anything actually captured?" check works exactly as before.
 *
 * Reuses [CallAudioRecorder.StartResult] so [CallRecordingService] can treat
 * both recorders the same way. Failure details are in [lastError].
 */
class ShizukuCallRecorder {

    private var sink: Sink? = null

    @Volatile
    var isRecording: Boolean = false
        private set

    /** Why the last [start] failed — shown to the user instead of a vague "unsupported". */
    @Volatile
    var lastError: String? = null
        private set

    fun start(outputFile: File): CallAudioRecorder.StartResult {
        lastError = null
        val service = ShizukuBridge.recorderService()
        if (service == null) {
            lastError = "Shizuku isn't connected"
            return CallAudioRecorder.StartResult.AllSourcesFailed
        }

        val writer = WavFileWriter(outputFile, CallAudioRecorder.SAMPLE_RATE_HZ)
        try {
            writer.open()
        } catch (e: IOException) {
            Log.e(TAG, "Couldn't open the output file for recording", e)
            lastError = "Couldn't create the recording file"
            return CallAudioRecorder.StartResult.AllSourcesFailed
        }

        val newSink = Sink(writer)
        val sources = AudioSourceCandidate.inTryOrder.map { it.mediaRecorderConstant }.toIntArray()

        val startedSource = try {
            service.start(sources, CallAudioRecorder.SAMPLE_RATE_HZ, newSink)
        } catch (t: Throwable) {
            Log.e(TAG, "Shizuku service.start failed", t)
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            newSink.close()
            return CallAudioRecorder.StartResult.AllSourcesFailed
        }

        if (startedSource < 0) {
            val serviceError: String? = try {
                service.lastError
            } catch (t: Throwable) {
                null
            }
            lastError = if (serviceError.isNullOrBlank()) {
                "no audio source could be opened (code $startedSource)"
            } else {
                serviceError
            }
            newSink.close()
            return CallAudioRecorder.StartResult.AllSourcesFailed
        }

        sink = newSink
        isRecording = true
        val candidate = AudioSourceCandidate.entries.firstOrNull { it.mediaRecorderConstant == startedSource }
            ?: AudioSourceCandidate.VOICE_CALL
        return CallAudioRecorder.StartResult.Started(candidate)
    }

    /** Stops recording and returns the average RMS across the whole capture (0.0 if nothing was captured). */
    suspend fun stop(): Double {
        val current = sink
        sink = null
        isRecording = false

        try {
            ShizukuBridge.recorderService()?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "Shizuku service.stop failed", t)
        }
        // The last chunks were sent oneway just before stop() returned; give
        // them a moment to arrive before the file is closed.
        delay(DRAIN_DELAY_MILLIS)

        return current?.close() ?: 0.0
    }

    /** Receives PCM from the shell process on a Binder thread. */
    private class Sink(private val writer: WavFileWriter) : IAudioSink.Stub() {
        private val lock = Any()
        private var closed = false
        private var failed = false
        private var rmsSum = 0.0
        private var rmsCount = 0
        private var bytesSinceHeaderFlush = 0L

        override fun onPcm(pcm: ByteArray?) {
            if (pcm == null || pcm.isEmpty()) return
            synchronized(lock) {
                if (closed || failed) return
                try {
                    writer.writePcmChunk(pcm, pcm.size)
                    rmsSum += PcmAmplitudeAnalyzer.rms(pcm, pcm.size)
                    rmsCount++

                    // Same crash-safety as CallAudioRecorder: keep the header current.
                    bytesSinceHeaderFlush += pcm.size
                    if (bytesSinceHeaderFlush >= HEADER_FLUSH_INTERVAL_BYTES) {
                        writer.flushHeader()
                        bytesSinceHeaderFlush = 0
                    }
                } catch (e: IOException) {
                    // Disk full / storage error — stop writing, keep what we have.
                    Log.e(TAG, "I/O error while writing recording; ignoring further audio", e)
                    failed = true
                }
            }
        }

        /** Finalizes the file and returns the average RMS. */
        fun close(): Double = synchronized(lock) {
            if (!closed) {
                closed = true
                try {
                    writer.close()
                } catch (e: IOException) {
                    Log.e(TAG, "Error closing recording file", e)
                }
            }
            if (rmsCount > 0) rmsSum / rmsCount else 0.0
        }
    }

    private companion object {
        const val TAG = "ShizukuCallRecorder"
        const val DRAIN_DELAY_MILLIS = 250L

        // 16 kHz mono PCM16 = 32,000 bytes/s, so this is roughly every 10 seconds.
        const val HEADER_FLUSH_INTERVAL_BYTES = 320_000L
    }
}
