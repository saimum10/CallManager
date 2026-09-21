package com.saimum.callmanager.telephony

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/** One SIM's discovered slot info paired with its last-requested forwarding config. */
data class SimForwardingSnapshot(
    val slot: SimSlotInfo,
    val config: ForwardingConfig
)

/**
 * Combines SIM slot discovery with each slot's persisted forwarding
 * config into one observable list. Shared by the Forwarding screen (which
 * lets the user edit it) and the Home dashboard (which only displays it),
 * so both always agree — there's exactly one place this combine logic lives.
 */
class ForwardingOverviewProvider(
    private val slotProvider: SimSlotProvider,
    private val store: ForwardingPreferencesStore?
) {
    suspend fun snapshots(): Flow<List<SimForwardingSnapshot>> {
        val slots = slotProvider.getActiveSlots()
        if (slots.isEmpty()) return flowOf(emptyList())

        val configFlows = slots.map { slot -> store?.configFor(slot.slotIndex) ?: flowOf(ForwardingConfig()) }
        return combine(configFlows) { configsArray ->
            slots.mapIndexed { index, slot -> SimForwardingSnapshot(slot, configsArray[index]) }
        }
    }
}
