package com.saimum.callmanager.recording

import java.io.File
import java.io.RandomAccessFile

/**
 * Writes mono PCM16 audio to a standard 44-byte-header WAV file.
 *
 * We use raw PCM/WAV rather than an encoded format (AAC/3GP) on purpose:
 * it needs no codec/MediaCodec pipeline (keeps dependencies minimal), and
 * raw PCM samples are what the RMS-based silence check in
 * [RecordingCapabilityProber] and post-call validation need anyway — no
 * decoding step required to analyze what was actually captured.
 */
class WavFileWriter(private val outputFile: File, private val sampleRateHz: Int) {

    private var randomAccessFile: RandomAccessFile? = null
    private var totalPcmBytesWritten: Long = 0

    fun open() {
        outputFile.parentFile?.mkdirs()
        val raf = RandomAccessFile(outputFile, "rw")
        raf.setLength(0)
        // Write a valid (zero-length) header right away, not a blank
        // placeholder — so even a file cut short by a crash is a readable WAV.
        // Real sizes get patched in by flushHeader() while recording and by close().
        writeHeader(raf, 0, sampleRateHz)
        randomAccessFile = raf
        totalPcmBytesWritten = 0
    }

    /**
     * Patches the header with the sizes written so far and returns to the
     * end of the file. Called periodically during a recording so that if
     * the process is killed mid-call, everything up to the last flush is
     * still a playable WAV instead of 44 bytes of blank header + raw audio.
     */
    fun flushHeader() {
        val raf = randomAccessFile ?: return
        writeHeader(raf, totalPcmBytesWritten, sampleRateHz)
        raf.seek(WAV_HEADER_SIZE + totalPcmBytesWritten)
    }

    fun writePcmChunk(buffer: ByteArray, length: Int) {
        val raf = randomAccessFile ?: return
        raf.write(buffer, 0, length)
        totalPcmBytesWritten += length
    }

    /** Finalizes the WAV header with real sizes and closes the file. */
    fun close() {
        val raf = randomAccessFile ?: return
        writeHeader(raf, totalPcmBytesWritten, sampleRateHz)
        raf.close()
        randomAccessFile = null
    }

    val bytesWritten: Long get() = totalPcmBytesWritten

    companion object {
        private const val WAV_HEADER_SIZE = 44
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16

        /**
         * Rewrites the header of an existing WAV so its sizes match the file's
         * actual length. Used to recover recordings left behind by a crash
         * (header never finalized, or blank in files from older versions).
         * Returns the number of PCM bytes the repaired file contains.
         */
        fun repairHeader(file: File, sampleRateHz: Int): Long =
            RandomAccessFile(file, "rw").use { raf ->
                val pcmBytes = (raf.length() - WAV_HEADER_SIZE).coerceAtLeast(0)
                val evenPcmBytes = pcmBytes - (pcmBytes % 2) // whole 16-bit samples only
                writeHeader(raf, evenPcmBytes, sampleRateHz)
                evenPcmBytes
            }

        private fun writeHeader(raf: RandomAccessFile, pcmDataSize: Long, sampleRateHz: Int) {
            val byteRate = sampleRateHz * CHANNELS * BITS_PER_SAMPLE / 8
            val blockAlign = CHANNELS * BITS_PER_SAMPLE / 8
            val riffChunkSize = 36 + pcmDataSize

            raf.seek(0)
            raf.writeBytes("RIFF")
            raf.write(intLe(riffChunkSize.toInt()))
            raf.writeBytes("WAVE")
            raf.writeBytes("fmt ")
            raf.write(intLe(16)) // PCM fmt chunk size
            raf.write(shortLe(1)) // audio format = 1 (PCM)
            raf.write(shortLe(CHANNELS))
            raf.write(intLe(sampleRateHz))
            raf.write(intLe(byteRate))
            raf.write(shortLe(blockAlign))
            raf.write(shortLe(BITS_PER_SAMPLE))
            raf.writeBytes("data")
            raf.write(intLe(pcmDataSize.toInt()))
        }

        private fun intLe(value: Int): ByteArray = byteArrayOf(
            (value and 0xff).toByte(),
            ((value shr 8) and 0xff).toByte(),
            ((value shr 16) and 0xff).toByte(),
            ((value shr 24) and 0xff).toByte()
        )

        private fun shortLe(value: Int): ByteArray = byteArrayOf(
            (value and 0xff).toByte(),
            ((value shr 8) and 0xff).toByte()
        )
    }
}
