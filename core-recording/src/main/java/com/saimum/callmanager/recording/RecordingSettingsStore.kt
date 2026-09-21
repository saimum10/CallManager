package com.saimum.callmanager.recording

import kotlinx.coroutines.flow.Flow

interface RecordingSettingsStore {
    val settings: Flow<RecordingSettings>
    suspend fun update(transform: (RecordingSettings) -> RecordingSettings)
}
