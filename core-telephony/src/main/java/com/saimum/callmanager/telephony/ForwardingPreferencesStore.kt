package com.saimum.callmanager.telephony

import kotlinx.coroutines.flow.Flow

/**
 * Persists what the user last configured per SIM slot **through this app**.
 *
 * This is deliberately NOT a carrier-verified status. Reading the network's
 * actual forwarding state requires READ_PRIVILEGED_PHONE_STATE or carrier
 * privileges — permissions a normal app can never obtain. The UI must
 * label whatever this store returns as "last requested," never as a
 * confirmed/verified state — see ForwardingDialer's doc comment for why.
 *
 * Defined here (in the feature module), implemented in the data module —
 * same dependency-inversion pattern as RecordingPreferencesStore.
 */
interface ForwardingPreferencesStore {
    fun configFor(slotIndex: Int): Flow<ForwardingConfig>
    suspend fun setConfig(slotIndex: Int, config: ForwardingConfig)
}
