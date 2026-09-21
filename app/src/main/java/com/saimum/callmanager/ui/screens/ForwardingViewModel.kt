package com.saimum.callmanager.ui.screens

import android.app.Application
import com.saimum.callmanager.telephony.CallForwardingIndicatorTracker
import com.saimum.callmanager.telephony.CarrierResponseInterpreter
import com.saimum.callmanager.telephony.ForwardingConfig
import com.saimum.callmanager.telephony.ForwardingDialResult
import com.saimum.callmanager.telephony.ForwardingDialer
import com.saimum.callmanager.telephony.ForwardingMmiCodes
import com.saimum.callmanager.telephony.ForwardingOverviewProvider
import com.saimum.callmanager.telephony.ForwardingReason
import com.saimum.callmanager.telephony.SimSlotInfo
import com.saimum.callmanager.telephony.SimSlotProvider
import com.saimum.callmanager.telephony.TelephonyServiceLocator
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.concurrent.Executors
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SimForwardingUiState(
    val slot: SimSlotInfo,
    val config: ForwardingConfig,
    val isBusy: Boolean = false,
    /** Real, network-verified status of UNCONDITIONAL forwarding — null until the tracker reports in. Doesn't cover the other 3 reasons; see "Check status." */
    val verifiedUnconditionalActive: Boolean? = null,
    /** Raw carrier response text from the last "Check status" tap, if any. */
    val lastCheckedStatusText: String? = null
)

data class ForwardingUiState(
    val loading: Boolean = true,
    val permissionDenied: Boolean = false,
    val simCards: List<SimForwardingUiState> = emptyList(),
    val message: String? = null
)

class ForwardingViewModel(application: Application) : AndroidViewModel(application) {

    private val slotProvider = SimSlotProvider(application)
    private val dialer = ForwardingDialer(application)
    private val store get() = TelephonyServiceLocator.forwardingPreferencesStore

    private val _uiState = MutableStateFlow(ForwardingUiState())
    val uiState: StateFlow<ForwardingUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    // One CFI tracker per active SIM slot, started when slots load and
    // stopped when the ViewModel is cleared — no persistent background
    // service, matches "only check while the screen is open."
    private val cfiTrackers = mutableMapOf<Int, CallForwardingIndicatorTracker>()
    private val cfiExecutor = Executors.newSingleThreadExecutor()

    fun onPermissionsGranted() {
        _uiState.update { it.copy(permissionDenied = false, loading = true) }
        loadSlots()
    }

    fun onPermissionsDenied() {
        _uiState.update { it.copy(permissionDenied = true, loading = false) }
    }

    private fun loadSlots() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            ForwardingOverviewProvider(slotProvider, store).snapshots().collect { snapshots ->
                val cards = snapshots.map { SimForwardingUiState(it.slot, it.config) }
                _uiState.update { state ->
                    state.copy(loading = false, simCards = mergeLocalState(cards, state.simCards))
                }
                startCfiTrackersIfNeeded(snapshots.map { it.slot })
            }
        }
    }

    /** Preserves in-flight busy/verified/checked state across a store re-emission (e.g. mid-dial). */
    private fun mergeLocalState(
        newCards: List<SimForwardingUiState>,
        currentCards: List<SimForwardingUiState>
    ): List<SimForwardingUiState> {
        val existingBySlot = currentCards.associateBy { it.slot.slotIndex }
        return newCards.map { fresh ->
            val existing = existingBySlot[fresh.slot.slotIndex]
            if (existing != null) {
                fresh.copy(
                    isBusy = existing.isBusy,
                    verifiedUnconditionalActive = existing.verifiedUnconditionalActive,
                    lastCheckedStatusText = existing.lastCheckedStatusText
                )
            } else {
                fresh
            }
        }
    }

    private fun startCfiTrackersIfNeeded(slots: List<SimSlotInfo>) {
        for (slot in slots) {
            if (cfiTrackers.containsKey(slot.slotIndex)) continue
            val tracker = CallForwardingIndicatorTracker(getApplication(), slot.subscriptionId)
            val started = tracker.start(cfiExecutor) { active ->
                updateCard(slot.slotIndex) { it.copy(verifiedUnconditionalActive = active) }
            }
            if (started) {
                cfiTrackers[slot.slotIndex] = tracker
            }
        }
    }

    /**
     * Blocked while forwarding is enabled for this SIM: the active MMI
     * registration was made for the *previous* reason, so silently
     * switching it here would desync the on-screen selection from what
     * the carrier actually has active. The user must turn forwarding off
     * (which sends the deactivation code for the current reason) before
     * picking a different one.
     */
    fun setReason(slotIndex: Int, reason: ForwardingReason) {
        val card = _uiState.value.simCards.find { it.slot.slotIndex == slotIndex } ?: return
        if (card.config.enabled) {
            showMessage("Turn off the current forwarding first")
            return
        }
        updateConfig(slotIndex) { it.copy(reason = reason) }
    }

    fun setDestinationNumber(slotIndex: Int, number: String) {
        updateConfig(slotIndex) { it.copy(destinationNumber = number) }
    }

    private fun updateConfig(slotIndex: Int, transform: (ForwardingConfig) -> ForwardingConfig) {
        val card = _uiState.value.simCards.find { it.slot.slotIndex == slotIndex } ?: return
        viewModelScope.launch {
            store?.setConfig(slotIndex, transform(card.config))
        }
    }

    /**
     * Dials the activation or deactivation MMI code for this SIM via
     * ForwardingDialer (TelephonyManager.sendUssdRequest — no dialer UI).
     * On success we now have the carrier's real response text, shown
     * directly instead of a generic "check your phone" message. The
     * persisted "enabled" state is still "last requested through this
     * app" — the carrier's actual acceptance is what the response text
     * (and, for Unconditional, the live CFI tracker) reflects.
     */
    fun toggleForwarding(card: SimForwardingUiState) {
        val turningOn = !card.config.enabled

        if (turningOn && card.config.destinationNumber.count { it.isDigit() } < MIN_DESTINATION_DIGITS) {
            showMessage("Enter a valid number first")
            return
        }

        setBusy(card.slot.slotIndex, true)
        viewModelScope.launch {
            val mmiCode = if (turningOn) {
                ForwardingMmiCodes.activationCode(card.config.reason, card.config.destinationNumber)
            } else {
                ForwardingMmiCodes.deactivationCode(card.config.reason)
            }
            val result = dialer.dial(mmiCode, card.slot.subscriptionId)
            setBusy(card.slot.slotIndex, false)

            when (result) {
                is ForwardingDialResult.Success -> {
                    // The carrier *answered* — but rejections arrive through the
                    // same callback as confirmations. Only record the new state
                    // when the reply doesn't read like a refusal.
                    if (CarrierResponseInterpreter.looksLikeFailure(result.carrierResponse)) {
                        showMessage("Carrier didn't confirm: ${result.carrierResponse}")
                    } else {
                        store?.setConfig(card.slot.slotIndex, card.config.copy(enabled = turningOn))
                        showMessage(result.carrierResponse)
                    }
                }
                is ForwardingDialResult.PermissionMissing -> showMessage("Permission required")
                is ForwardingDialResult.Failed -> showMessage(result.reason)
            }
        }
    }

    /** Sends an interrogation code and shows the carrier's real status text for this SIM's currently selected forwarding type. */
    fun checkStatus(card: SimForwardingUiState) {
        setBusy(card.slot.slotIndex, true)
        viewModelScope.launch {
            val result = dialer.dial(ForwardingMmiCodes.interrogationCode(card.config.reason), card.slot.subscriptionId)
            setBusy(card.slot.slotIndex, false)

            when (result) {
                is ForwardingDialResult.Success ->
                    updateCard(card.slot.slotIndex) { it.copy(lastCheckedStatusText = result.carrierResponse) }
                is ForwardingDialResult.PermissionMissing -> showMessage("Permission required")
                is ForwardingDialResult.Failed -> showMessage(result.reason)
            }
        }
    }

    /**
     * Atomic (`update`), not read-then-assign: the CFI callbacks arrive on
     * [cfiExecutor]'s thread while taps and dial results arrive on the main
     * thread, and two interleaved read-modify-writes would silently drop one
     * of the changes.
     */
    private fun updateCard(slotIndex: Int, transform: (SimForwardingUiState) -> SimForwardingUiState) {
        _uiState.update { state ->
            state.copy(
                simCards = state.simCards.map {
                    if (it.slot.slotIndex == slotIndex) transform(it) else it
                }
            )
        }
    }

    private fun setBusy(slotIndex: Int, busy: Boolean) {
        updateCard(slotIndex) { it.copy(isBusy = busy) }
    }

    private fun showMessage(message: String) {
        _uiState.update { it.copy(message = message) }
    }

    fun consumeMessage() {
        _uiState.update { it.copy(message = null) }
    }

    override fun onCleared() {
        cfiTrackers.values.forEach { it.stop() }
        cfiTrackers.clear()
        cfiExecutor.shutdown()
        super.onCleared()
    }

    private companion object {
        /** Shortest number worth sending to the carrier — guards against a lone "+" or a couple of stray digits. */
        const val MIN_DESTINATION_DIGITS = 3
    }
}
