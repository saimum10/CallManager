package com.saimum.callmanager.recording

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class RecordingLiveState {
    data object Idle : RecordingLiveState()
    data class Recording(val direction: CallDirection, val startedAtMillis: Long) : RecordingLiveState()
}

/** One-shot, honest outcomes — never a generic "success" when something failed. */
sealed class RecordingEvent {
    data object DeviceUnsupported : RecordingEvent()
    /** RECORD_AUDIO was revoked after being granted — not a device limitation, don't poison RecordingCapability over this. */
    data object PermissionRevoked : RecordingEvent()
    data class SavedButSilent(val fileName: String) : RecordingEvent()
    data class Saved(val fileName: String) : RecordingEvent()
}

/**
 * Bridges [CallRecordingService] (a Service — no direct UI reference) and
 * the Compose UI observing it. A plain in-process singleton is enough
 * here: everything runs in one process, so a bound-service/AIDL setup
 * would just be extra weight for no real benefit.
 */
object RecordingStatusBus {
    private val _liveState = MutableStateFlow<RecordingLiveState>(RecordingLiveState.Idle)
    val liveState: StateFlow<RecordingLiveState> = _liveState.asStateFlow()

    private val _events = MutableSharedFlow<RecordingEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<RecordingEvent> = _events.asSharedFlow()

    fun publishLiveState(state: RecordingLiveState) {
        _liveState.value = state
    }

    fun publishEvent(event: RecordingEvent) {
        _events.tryEmit(event)
    }
}
