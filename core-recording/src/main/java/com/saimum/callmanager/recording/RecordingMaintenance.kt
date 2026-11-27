package com.saimum.callmanager.recording

import java.io.File
import kotlinx.coroutines.flow.first

/**
 * Applies the two retention policies from Settings against real files —
 * actually deletes qualifying recordings (DB row + file on disk), never
 * just marks them.
 *
 * Run right after a new recording is saved, and once at app startup to
 * catch age-based expiry even on days with no new recordings. This is a
 * deliberately simple trigger model (not a WorkManager periodic job) — see
 * project notes on why.
 */
class RecordingMaintenance(private val libraryStore: RecordingLibraryStore) {

    suspend fun applyRetentionPolicies(settings: RecordingSettings) {
        val recordings = libraryStore.recordings.first()
        val now = System.currentTimeMillis()

        val maxAgeMillis = settings.autoDeleteInterval.toMaxAgeMillisOrNull()
        val afterAgeDelete = if (maxAgeMillis != null) {
            val (expired, kept) = recordings.partition { now - it.createdAtMillis > maxAgeMillis }
            expired.forEach { deleteRecording(it) }
            kept
        } else {
            recordings
        }

        val maxBytes = settings.maxStorageBytes ?: return
        var totalBytes = afterAgeDelete.sumOf { sizeOf(it) }
        if (totalBytes <= maxBytes) return

        for (item in afterAgeDelete.sortedBy { it.createdAtMillis }) {
            if (totalBytes <= maxBytes) break
            totalBytes -= sizeOf(item)
            deleteRecording(item)
        }
    }

    /**
     * Adopts recordings that exist on disk but not in the library — left
     * behind when the process died mid-call, or when the library write
     * failed. Without this they'd sit in storage forever: invisible in the
     * app, unplayable (header never finalized) and not counted toward the
     * storage limit.
     *
     * Skips anything modified in the last minute (it may be a recording
     * that's being written right now) and deletes zero-audio leftovers.
     * Direction can't be recovered when the file name has no direction
     * prefix (the "include direction" setting was off) — those default to
     * INCOMING; duration and time are estimated from file size/modified time.
     */
    suspend fun recoverOrphanedRecordings(recordingsDir: File) {
        val files = recordingsDir.listFiles { file ->
            file.isFile && file.extension.equals("wav", ignoreCase = true)
        } ?: return
        val knownPaths = libraryStore.recordings.first().map { it.filePath }.toSet()
        val now = System.currentTimeMillis()

        for (file in files) {
            if (file.absolutePath in knownPaths) continue
            if (now - file.lastModified() < ORPHAN_MIN_IDLE_MILLIS) continue

            val pcmBytes = runCatching {
                WavFileWriter.repairHeader(file, CallAudioRecorder.SAMPLE_RATE_HZ)
            }.getOrNull() ?: continue

            if (pcmBytes <= 0L) {
                runCatching { file.delete() }
                continue
            }

            val bytesPerSecond = CallAudioRecorder.SAMPLE_RATE_HZ * 2L // mono, 16-bit
            val durationMillis = pcmBytes * 1000L / bytesPerSecond
            val direction = CallDirection.entries.firstOrNull { file.name.startsWith("${it.name}_") }
                ?: CallDirection.INCOMING

            libraryStore.addRecording(
                RecordingLibraryItem(
                    id = 0,
                    filePath = file.absolutePath,
                    fileName = file.name,
                    direction = direction,
                    createdAtMillis = (file.lastModified() - durationMillis).coerceAtLeast(0L),
                    durationMillis = durationMillis,
                    isSilent = false // not analyzed — unknown, so not flagged
                )
            )
        }
    }

    private suspend fun deleteRecording(item: RecordingLibraryItem) {
        libraryStore.deleteRecording(item)
        runCatching { File(item.filePath).delete() }
    }

    private fun sizeOf(item: RecordingLibraryItem): Long =
        runCatching { File(item.filePath).length() }.getOrDefault(0L)

    private companion object {
        const val ORPHAN_MIN_IDLE_MILLIS = 60_000L
    }
}
