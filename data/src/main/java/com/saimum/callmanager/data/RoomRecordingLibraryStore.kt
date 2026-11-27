package com.saimum.callmanager.data

import android.content.Context
import android.util.Log
import com.saimum.callmanager.recording.CallDirection
import com.saimum.callmanager.recording.RecordingLibraryItem
import com.saimum.callmanager.recording.RecordingLibraryStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private const val TAG = "RecordingLibraryStore"

class RoomRecordingLibraryStore(context: Context) : RecordingLibraryStore {

    private val dao = CallManagerDatabase.getInstance(context).recordingDao()

    override val recordings: Flow<List<RecordingLibraryItem>> =
        dao.observeAll()
            .catch { exception ->
                Log.e(TAG, "Failed to read the recordings library, showing an empty list", exception)
                emit(emptyList())
            }
            .map { entities -> entities.map { it.toLibraryItem() } }

    /**
     * Returns -1 on failure instead of throwing — this gets called from
     * CallRecordingService right after a real call ends; a Room hiccup here
     * must never crash the service mid-call. The recorded audio file itself
     * is unaffected either way; only its library entry would be missing.
     */
    override suspend fun addRecording(item: RecordingLibraryItem): Long = try {
        dao.insert(item.toEntity())
    } catch (e: Exception) {
        Log.e(TAG, "Failed to save recording to the library", e)
        -1L
    }

    override suspend fun deleteRecording(item: RecordingLibraryItem) = safeWrite(TAG) {
        dao.delete(item.toEntity())
    }

    private fun RecordingEntity.toLibraryItem() = RecordingLibraryItem(
        id = id,
        filePath = filePath,
        fileName = fileName,
        direction = runCatching { CallDirection.valueOf(direction) }.getOrDefault(CallDirection.INCOMING),
        createdAtMillis = createdAtMillis,
        durationMillis = durationMillis,
        isSilent = isSilent
    )

    private fun RecordingLibraryItem.toEntity() = RecordingEntity(
        id = id,
        filePath = filePath,
        fileName = fileName,
        direction = direction.name,
        createdAtMillis = createdAtMillis,
        durationMillis = durationMillis,
        isSilent = isSilent
    )
}
