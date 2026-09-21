package com.saimum.callmanager.recording

/**
 * Minimal manual dependency-injection point.
 *
 * core-recording defines [RecordingPreferencesStore] as an interface; the
 * data module provides the real (DataStore-backed) implementation. The
 * app module wires the concrete instance in once at startup (see
 * CallManagerApp), so components the OS instantiates directly —
 * [CallRecordingService] included — can still reach it without
 * core-recording ever depending on the data module.
 *
 * A small object like this is a deliberate choice over pulling in a full
 * DI framework (Hilt/Dagger) for an app this size — "keep dependencies
 * minimal" applies to build tooling weight, not just library count.
 */
object RecordingServiceLocator {
    @Volatile
    var preferencesStore: RecordingPreferencesStore? = null

    @Volatile
    var libraryStore: RecordingLibraryStore? = null

    @Volatile
    var settingsStore: RecordingSettingsStore? = null
}
