package com.saimum.callmanager.recording

import kotlinx.coroutines.flow.Flow

/** The three toggles from the Call Recording screen: master, incoming, outgoing. */
data class RecordingPreferences(
    val masterEnabled: Boolean = false,
    val incomingEnabled: Boolean = false,
    val outgoingEnabled: Boolean = false
)

/**
 * Persists recording preferences and the last-known [RecordingCapability].
 *
 * Defined here (in the feature module) rather than in the data module on
 * purpose: core-recording shouldn't need to know or care that the real
 * implementation happens to use DataStore. The app module wires the
 * concrete implementation in at startup.
 */
interface RecordingPreferencesStore {
    val preferences: Flow<RecordingPreferences>
    suspend fun setMasterEnabled(enabled: Boolean)
    suspend fun setIncomingEnabled(enabled: Boolean)
    suspend fun setOutgoingEnabled(enabled: Boolean)

    /** Cached so we don't have to re-probe/re-validate on every app launch. */
    val capability: Flow<RecordingCapability>
    suspend fun setCapability(capability: RecordingCapability)
}
