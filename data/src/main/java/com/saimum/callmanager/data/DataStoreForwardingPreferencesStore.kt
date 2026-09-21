package com.saimum.callmanager.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.saimum.callmanager.telephony.ForwardingConfig
import com.saimum.callmanager.telephony.ForwardingPreferencesStore
import com.saimum.callmanager.telephony.ForwardingReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.forwardingDataStore by preferencesDataStore(name = "forwarding_preferences")
private const val TAG = "ForwardingPreferencesStore"

/**
 * Keys are per physical slot index (0, 1, ...) rather than per subscription
 * ID on purpose: "SIM 1 always forwards this way" should survive swapping
 * which physical SIM card sits in that slot.
 */
class DataStoreForwardingPreferencesStore(private val context: Context) : ForwardingPreferencesStore {

    private fun enabledKey(slot: Int) = booleanPreferencesKey("sim${slot}_enabled")
    private fun reasonKey(slot: Int) = stringPreferencesKey("sim${slot}_reason")
    private fun numberKey(slot: Int) = stringPreferencesKey("sim${slot}_number")

    override fun configFor(slotIndex: Int): Flow<ForwardingConfig> =
        context.forwardingDataStore.data.recoverFromReadErrors(TAG).map { prefs ->
            ForwardingConfig(
                enabled = prefs[enabledKey(slotIndex)] ?: false,
                reason = prefs[reasonKey(slotIndex)]?.let { name ->
                    runCatching { ForwardingReason.valueOf(name) }.getOrDefault(ForwardingReason.UNCONDITIONAL)
                } ?: ForwardingReason.UNCONDITIONAL,
                destinationNumber = prefs[numberKey(slotIndex)] ?: ""
            )
        }

    override suspend fun setConfig(slotIndex: Int, config: ForwardingConfig) = safeWrite(TAG) {
        context.forwardingDataStore.edit { prefs ->
            prefs[enabledKey(slotIndex)] = config.enabled
            prefs[reasonKey(slotIndex)] = config.reason.name
            prefs[numberKey(slotIndex)] = config.destinationNumber
        }
    }
}
