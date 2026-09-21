package com.saimum.callmanager.recording

import kotlinx.coroutines.flow.Flow

/** One saved recording, independent of how/where it's actually stored. */
data class RecordingLibraryItem(
    val id: Long,
    val filePath: String,
    val fileName: String,
    val direction: CallDirection,
    val createdAtMillis: Long,
    val durationMillis: Long,
    /** True if this recording was analyzed and found to contain no real audio. */
    val isSilent: Boolean
)

/**
 * Persists the list of saved recordings. Defined here for the same reason
 * as [RecordingPreferencesStore]: core-recording (and CallRecordingService,
 * which the OS instantiates directly) shouldn't need to know Room exists —
 * the data module provides the real implementation, wired in by the app
 * module at startup via [RecordingServiceLocator].
 */
interface RecordingLibraryStore {
    val recordings: Flow<List<RecordingLibraryItem>>
    suspend fun addRecording(item: RecordingLibraryItem): Long
    suspend fun deleteRecording(item: RecordingLibraryItem)
}
