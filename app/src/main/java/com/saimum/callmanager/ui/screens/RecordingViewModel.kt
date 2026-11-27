package com.saimum.callmanager.ui.screens

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.saimum.callmanager.recording.CallRecordingService
import com.saimum.callmanager.recording.RecordingCapability
import com.saimum.callmanager.recording.RecordingCapabilityProber
import com.saimum.callmanager.recording.RecordingEvent
import com.saimum.callmanager.recording.RecordingLibraryItem
import com.saimum.callmanager.recording.RecordingLiveState
import com.saimum.callmanager.recording.RecordingPreferences
import com.saimum.callmanager.recording.RecordingServiceLocator
import com.saimum.callmanager.recording.RecordingStatusBus
import com.saimum.callmanager.recording.shizuku.ShizukuBridge
import com.saimum.callmanager.recording.shizuku.ShizukuState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Local (not persisted) playback state — which recording, if any, is currently loaded. */
data class PlaybackState(
    val playingId: Long? = null,
    val isPaused: Boolean = false
)

data class RecordingUiState(
    val preferences: RecordingPreferences = RecordingPreferences(),
    val capability: RecordingCapability = RecordingCapability.UNKNOWN,
    val liveState: RecordingLiveState = RecordingLiveState.Idle,
    val lastEvent: RecordingEvent? = null,
    val permissionDenied: Boolean = false,
    val recordings: List<RecordingLibraryItem> = emptyList(),
    val playback: PlaybackState = PlaybackState(),
    val playbackError: String? = null,
    val shizukuState: ShizukuState = ShizukuState.NOT_RUNNING,
    /** True while Shizuku is busy granting permissions. */
    val shizukuBusy: Boolean = false,
    /** Last result/problem from the Shizuku card, shown under it. */
    val shizukuMessage: String? = null
)

class RecordingViewModel(application: Application) : AndroidViewModel(application) {

    private val store get() = RecordingServiceLocator.preferencesStore
    private val libraryStore get() = RecordingServiceLocator.libraryStore
    private val prober = RecordingCapabilityProber()
    private var mediaPlayer: MediaPlayer? = null

    private val _uiState = MutableStateFlow(RecordingUiState())
    val uiState: StateFlow<RecordingUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            store?.preferences?.collect { prefs ->
                _uiState.value = _uiState.value.copy(preferences = prefs)
            }
        }
        viewModelScope.launch {
            store?.capability?.collect { cap ->
                _uiState.value = _uiState.value.copy(capability = cap)
            }
        }
        viewModelScope.launch {
            RecordingStatusBus.liveState.collect { live ->
                _uiState.value = _uiState.value.copy(liveState = live)
            }
        }
        viewModelScope.launch {
            RecordingStatusBus.events.collect { event ->
                _uiState.value = _uiState.value.copy(lastEvent = event)
            }
        }
        viewModelScope.launch {
            libraryStore?.recordings?.collect { list ->
                _uiState.value = _uiState.value.copy(recordings = list)
            }
        }
        viewModelScope.launch {
            ShizukuBridge.state.collect { shizukuState ->
                _uiState.value = _uiState.value.copy(shizukuState = shizukuState)
            }
        }
        ShizukuBridge.refresh()
    }

    // --- Shizuku ---------------------------------------------------------

    /** Re-reads Shizuku's state (e.g. after the user started it or came back from its app). */
    fun refreshShizuku() {
        ShizukuBridge.refresh()
        _uiState.value = _uiState.value.copy(shizukuMessage = ShizukuBridge.lastProblem)
    }

    /** Shows Shizuku's own "Allow Call Manager?" dialog. */
    fun requestShizukuPermission() {
        ShizukuBridge.requestPermission()
        _uiState.value = _uiState.value.copy(shizukuMessage = ShizukuBridge.lastProblem)
    }

    /**
     * Has Shizuku (shell user) grant the app its runtime permissions — no
     * permission dialogs. If [thenEnable] and everything needed ends up
     * granted, call recording is switched on right away.
     */
    fun grantPermissionsViaShizuku(thenEnable: Boolean) {
        val app = getApplication<Application>()
        _uiState.value = _uiState.value.copy(shizukuBusy = true, shizukuMessage = null)
        viewModelScope.launch {
            val log = withContext(Dispatchers.IO) {
                ShizukuBridge.grantPermissions(app.packageName, permissionsToGrantViaShizuku())
            }
            val granted = hasRequiredRecordingPermissions()
            _uiState.value = _uiState.value.copy(
                shizukuBusy = false,
                shizukuMessage = when {
                    granted -> "Permissions granted through Shizuku"
                    log != null -> log
                    else -> "Shizuku isn't ready yet"
                }
            )
            if (granted) {
                if (thenEnable) attemptEnableMaster()
            } else if (thenEnable) {
                onPermissionsDenied()
            }
        }
    }

    private fun permissionsToGrantViaShizuku(): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun hasRequiredRecordingPermissions(): Boolean {
        val app = getApplication<Application>()
        return listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_PHONE_STATE).all {
            ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** Call once RECORD_AUDIO + READ_PHONE_STATE are confirmed granted (notification permission is optional). */
    fun attemptEnableMaster() {
        _uiState.value = _uiState.value.copy(permissionDenied = false)
        // With Shizuku the real capture happens in the Shizuku service, so the
        // in-app source probe (which says "unsupported" without VOICE_CALL) would
        // be misleading — real confirmation comes from the first recorded call.
        val probeResult = if (ShizukuBridge.isReady()) RecordingCapability.UNKNOWN else prober.probeInitOnly()
        viewModelScope.launch {
            store?.setCapability(probeResult)
            if (probeResult != RecordingCapability.INIT_UNSUPPORTED) {
                store?.setMasterEnabled(true)
                CallRecordingService.startListening(getApplication())
            }
            // If INIT_UNSUPPORTED, we deliberately do NOT enable the master
            // toggle — the persisted capability drives the UI's
            // "Recording unavailable on this device" banner instead.
        }
    }

    fun disableMaster() {
        viewModelScope.launch { store?.setMasterEnabled(false) }
        CallRecordingService.stopListening(getApplication())
    }

    fun setIncomingEnabled(enabled: Boolean) {
        viewModelScope.launch { store?.setIncomingEnabled(enabled) }
    }

    fun setOutgoingEnabled(enabled: Boolean) {
        viewModelScope.launch { store?.setOutgoingEnabled(enabled) }
    }

    fun stopActiveRecording() {
        CallRecordingService.requestStopRecording(getApplication())
    }

    fun onPermissionsDenied() {
        _uiState.value = _uiState.value.copy(permissionDenied = true)
    }

    fun consumeLastEvent() {
        _uiState.value = _uiState.value.copy(lastEvent = null)
    }

    fun consumePermissionDenied() {
        _uiState.value = _uiState.value.copy(permissionDenied = false)
    }

    fun consumePlaybackError() {
        _uiState.value = _uiState.value.copy(playbackError = null)
    }

    /** Play / pause / resume toggle for one recording, mutually exclusive with any other. */
    fun togglePlayback(item: RecordingLibraryItem) {
        val playback = _uiState.value.playback
        when {
            playback.playingId == item.id && !playback.isPaused -> {
                // MediaPlayer.pause()/start() throw IllegalStateException if
                // the player isn't in the expected state — which can happen
                // if an internal playback error left it in a broken state
                // between UI updates. Never let that crash the app.
                try {
                    mediaPlayer?.pause()
                    _uiState.value = _uiState.value.copy(playback = playback.copy(isPaused = true))
                } catch (e: IllegalStateException) {
                    handlePlaybackFailure()
                }
            }
            playback.playingId == item.id && playback.isPaused -> {
                try {
                    mediaPlayer?.start()
                    _uiState.value = _uiState.value.copy(playback = playback.copy(isPaused = false))
                } catch (e: IllegalStateException) {
                    handlePlaybackFailure()
                }
            }
            else -> startPlayback(item)
        }
    }

    private fun startPlayback(item: RecordingLibraryItem) {
        releasePlayer()
        val player = MediaPlayer()
        try {
            player.setDataSource(item.filePath)
            player.setOnCompletionListener {
                _uiState.value = _uiState.value.copy(playback = PlaybackState())
                releasePlayer()
            }
            // Without this, an internal playback error (corrupted file,
            // I/O hiccup mid-playback) leaves the player silently stuck in
            // MediaPlayer's "Error" state — OnCompletionListener never
            // fires for it, so the UI keeps showing "playing", and the next
            // Pause/Resume tap throws IllegalStateException uncaught.
            player.setOnErrorListener { _, _, _ ->
                handlePlaybackFailure()
                true // we've handled it — stops the framework from also invoking OnCompletionListener
            }
            player.prepare()
            player.start()
            mediaPlayer = player
            _uiState.value = _uiState.value.copy(playback = PlaybackState(playingId = item.id, isPaused = false))
        } catch (e: Exception) {
            // IOException (bad/missing file) or IllegalStateException/
            // IllegalArgumentException (bad state or unsupported format) —
            // all end up as the same honest "can't play this" outcome.
            player.release()
            _uiState.value = _uiState.value.copy(
                playback = PlaybackState(),
                playbackError = "Unable to play this recording"
            )
        }
    }

    private fun handlePlaybackFailure() {
        releasePlayer()
        _uiState.value = _uiState.value.copy(
            playback = PlaybackState(),
            playbackError = "Playback stopped unexpectedly"
        )
    }

    fun deleteRecording(item: RecordingLibraryItem) {
        if (_uiState.value.playback.playingId == item.id) {
            releasePlayer()
            _uiState.value = _uiState.value.copy(playback = PlaybackState())
        }
        viewModelScope.launch {
            libraryStore?.deleteRecording(item)
            runCatching { File(item.filePath).delete() }
        }
    }

    private fun releasePlayer() {
        mediaPlayer?.apply {
            runCatching { stop() }
            release()
        }
        mediaPlayer = null
    }

    override fun onCleared() {
        releasePlayer()
        super.onCleared()
    }
}
