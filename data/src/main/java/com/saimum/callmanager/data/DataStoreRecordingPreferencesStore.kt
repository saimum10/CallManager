package com.saimum.callmanager.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.saimum.callmanager.recording.RecordingCapability
import com.saimum.callmanager.recording.RecordingPreferences
import com.saimum.callmanager.recording.RecordingPreferencesStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.recordingDataStore by preferencesDataStore(name = "recording_preferences")
private const val TAG = "RecordingPreferencesStore"

/**
 * Real, DataStore-backed implementation of the interface core-recording
 * defines. Nothing in core-recording knows this class exists — it only
 * ever talks to [RecordingPreferencesStore].
 */
class DataStoreRecordingPreferencesStore(private val context: Context) : RecordingPreferencesStore {

    private object Keys {
        val MASTER_ENABLED = booleanPreferencesKey("master_enabled")
        val INCOMING_ENABLED = booleanPreferencesKey("incoming_enabled")
        val OUTGOING_ENABLED = booleanPreferencesKey("outgoing_enabled")
        val CAPABILITY = stringPreferencesKey("capability")
    }

    override val preferences: Flow<RecordingPreferences> =
        context.recordingDataStore.data.recoverFromReadErrors(TAG).map { prefs ->
            RecordingPreferences(
                masterEnabled = prefs[Keys.MASTER_ENABLED] ?: false,
                incomingEnabled = prefs[Keys.INCOMING_ENABLED] ?: false,
                outgoingEnabled = prefs[Keys.OUTGOING_ENABLED] ?: false
            )
        }

    override suspend fun setMasterEnabled(enabled: Boolean) = safeWrite(TAG) {
        context.recordingDataStore.edit { prefs ->
            prefs[Keys.MASTER_ENABLED] = enabled
            if (!enabled) {
                // Master off forces both children off at the storage layer
                // too — this invariant from the spec holds even if a future
                // UI bug tries to set a child true while master is false.
                prefs[Keys.INCOMING_ENABLED] = false
                prefs[Keys.OUTGOING_ENABLED] = false
            }
        }
    }

    override suspend fun setIncomingEnabled(enabled: Boolean) = safeWrite(TAG) {
        context.recordingDataStore.edit { it[Keys.INCOMING_ENABLED] = enabled }
    }

    override suspend fun setOutgoingEnabled(enabled: Boolean) = safeWrite(TAG) {
        context.recordingDataStore.edit { it[Keys.OUTGOING_ENABLED] = enabled }
    }

    override val capability: Flow<RecordingCapability> =
        context.recordingDataStore.data.recoverFromReadErrors(TAG).map { prefs ->
            prefs[Keys.CAPABILITY]?.let { name ->
                runCatching { RecordingCapability.valueOf(name) }.getOrDefault(RecordingCapability.UNKNOWN)
            } ?: RecordingCapability.UNKNOWN
        }

    override suspend fun setCapability(capability: RecordingCapability) = safeWrite(TAG) {
        context.recordingDataStore.edit { it[Keys.CAPABILITY] = capability.name }
    }
}
