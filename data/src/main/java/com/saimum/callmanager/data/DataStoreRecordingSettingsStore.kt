package com.saimum.callmanager.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.saimum.callmanager.recording.AutoDeleteInterval
import com.saimum.callmanager.recording.RecordingSettings
import com.saimum.callmanager.recording.RecordingSettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.recordingSettingsDataStore by preferencesDataStore(name = "recording_settings")
private const val TAG = "RecordingSettingsStore"

class DataStoreRecordingSettingsStore(private val context: Context) : RecordingSettingsStore {

    private object Keys {
        val INCLUDE_DIRECTION = booleanPreferencesKey("include_direction_in_filename")
        val AUTO_DELETE = stringPreferencesKey("auto_delete_interval")
        val MAX_STORAGE_BYTES = longPreferencesKey("max_storage_bytes")
        val NOTIFY_ON_FINISH = booleanPreferencesKey("notify_on_finish")
        val CUSTOM_FOLDER_URI = stringPreferencesKey("custom_folder_uri")
        val FORCE_SPEAKERPHONE = booleanPreferencesKey("force_speakerphone_while_recording")
    }

    override val settings: Flow<RecordingSettings> =
        context.recordingSettingsDataStore.data.recoverFromReadErrors(TAG).map { it.toRecordingSettings() }

    override suspend fun update(transform: (RecordingSettings) -> RecordingSettings) = safeWrite(TAG) {
        context.recordingSettingsDataStore.edit { prefs ->
            val updated = transform(prefs.toRecordingSettings())
            prefs[Keys.INCLUDE_DIRECTION] = updated.includeDirectionInFileName
            prefs[Keys.AUTO_DELETE] = updated.autoDeleteInterval.name
            prefs[Keys.NOTIFY_ON_FINISH] = updated.notifyOnFinish
            prefs[Keys.FORCE_SPEAKERPHONE] = updated.forceSpeakerphoneWhileRecording

            // Bound to locals before the null-check: Kotlin can't smart-cast a
            // property declared in another Gradle module (core-recording here)
            // straight from `updated.maxStorageBytes != null`, even though it's
            // a plain val — cross-module ABI visibility rules block it. A local
            // val is always smart-castable.
            val maxStorageBytes = updated.maxStorageBytes
            if (maxStorageBytes != null) {
                prefs[Keys.MAX_STORAGE_BYTES] = maxStorageBytes
            } else {
                prefs.remove(Keys.MAX_STORAGE_BYTES)
            }

            val customFolderUri = updated.customFolderUri
            if (customFolderUri != null) {
                prefs[Keys.CUSTOM_FOLDER_URI] = customFolderUri
            } else {
                prefs.remove(Keys.CUSTOM_FOLDER_URI)
            }
        }
    }

    private fun Preferences.toRecordingSettings(): RecordingSettings = RecordingSettings(
        includeDirectionInFileName = this[Keys.INCLUDE_DIRECTION] ?: true,
        autoDeleteInterval = this[Keys.AUTO_DELETE]?.let { name ->
            runCatching { AutoDeleteInterval.valueOf(name) }.getOrDefault(AutoDeleteInterval.OFF)
        } ?: AutoDeleteInterval.OFF,
        maxStorageBytes = this[Keys.MAX_STORAGE_BYTES],
        notifyOnFinish = this[Keys.NOTIFY_ON_FINISH] ?: true,
        customFolderUri = this[Keys.CUSTOM_FOLDER_URI],
        forceSpeakerphoneWhileRecording = this[Keys.FORCE_SPEAKERPHONE] ?: false
    )
}
