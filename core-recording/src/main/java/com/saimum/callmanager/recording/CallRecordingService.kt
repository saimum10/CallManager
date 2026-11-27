package com.saimum.callmanager.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.saimum.callmanager.recording.shizuku.ShizukuBridge
import com.saimum.callmanager.recording.shizuku.ShizukuState
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Owns the real call-recording lifecycle:
 * - watches call state via [CallStateTracker]
 * - starts/stops [CallAudioRecorder] against real calls only
 * - reports honest outcomes via [RecordingStatusBus]: a silent capture is
 *   reported as silent, not as a generic success
 *
 * Lifecycle: this is NOT a 24/7 service any more. [CallStateReceiver]
 * (a manifest receiver for PHONE_STATE — woken by Android, no process needs
 * to be alive) starts this service as a foreground (microphone type) service
 * only when a call goes off-hook and recording is enabled for that
 * direction. The service then watches that one call with [CallStateTracker],
 * records it, saves it and calls stopSelf() when the call ends (or the Stop
 * button is used) — so nothing keeps running between calls and the process
 * can be reclaimed by Android immediately afterwards.
 *
 * Android 12+ only lets a foreground service be started from the background
 * for apps that are exempt (e.g. exempted from battery optimization, which
 * Shizuku sets up for this app). If Android refuses, [startForCall] returns
 * false and that call is not recorded — see the README.
 *
 * Capture engine: when Shizuku is connected ([ShizukuBridge.isReady]) the call
 * audio is recorded by the Shizuku user service running as the shell user
 * ([ShizukuCallRecorder]) — the only way a normal app gets the real VOICE_CALL
 * source on Android 10+. If Shizuku isn't ready, or can't open a source, the
 * regular in-app [CallAudioRecorder] is used as before.
 */
class CallRecordingService : Service() {

    private val serviceJob = SupervisorJob()
    /**
     * Without an exception handler here, ANY uncaught exception in a
     * background coroutine (a Room hiccup, a file-system error, anything)
     * would crash the whole app — potentially in the middle of an active
     * call. This handler is the last line of defense: log it, don't crash.
     */
    private val serviceExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Unhandled error in a recording-service coroutine", throwable)
    }
    private val serviceScope = CoroutineScope(serviceJob + serviceExceptionHandler)
    private val callbackExecutor = Executors.newSingleThreadExecutor()

    private lateinit var callStateTracker: CallStateTracker
    private val audioRecorder = CallAudioRecorder()
    private val shizukuRecorder = ShizukuCallRecorder()
    /** True while the current recording is being captured through Shizuku rather than in-app. */
    private var usingShizuku = false
    private val isCapturing: Boolean get() = audioRecorder.isRecording || shizukuRecorder.isRecording
    private var audioManager: AudioManager? = null
    /** Non-null only while [RecordingSettings.forceSpeakerphoneWhileRecording] override is active. */
    private var speakerOverrideJob: Job? = null
    private val folderCopier by lazy { RecordingFolderCopier(this) }
    /**
     * Serializes the start and finish critical sections. A call ending, the
     * notification's Stop button and a freshly-detected call can all race —
     * with one mutex around both start and finish, "is a recording running?"
     * is always checked and acted on atomically.
     */
    private val lifecycleMutex = Mutex()

    /**
     * True from the moment the phone goes off-hook until it returns to idle.
     * Set on the call-state callback thread, read inside [startRecording]
     * under [lifecycleMutex] — this is what stops a recording from starting
     * *after* its call already ended (Idle event overtaking the start
     * coroutine), which would otherwise leave it running with no way to stop.
     */
    @Volatile
    private var callActive = false

    /** True once the service has successfully entered the foreground (see [onCreate]). */
    private var isForeground = false

    /** True while [callStateTracker] is watching the current call (one call = one session). */
    @Volatile
    private var trackerActive = false

    /**
     * startId of the command that began (or last re-confirmed) the current
     * session. Used with stopSelf(startId) so that finishing call #1 can never
     * stop a service that a newer call #2 has already been started on.
     */
    @Volatile
    private var sessionStartId = 0

    private var store: RecordingPreferencesStore? = null
    private var libraryStore: RecordingLibraryStore? = null
    private var settingsStore: RecordingSettingsStore? = null
    private var currentPrefs: RecordingPreferences = RecordingPreferences()
    private var currentSettings: RecordingSettings = RecordingSettings()
    private var currentDirection: CallDirection? = null
    private var currentOutputFile: File? = null
    private var recordingStartedAtMillis: Long = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()

        // Must happen right away: this service is started with
        // startForegroundService() (by CallStateReceiver), which gives us only
        // a few seconds to call startForeground() — including on the
        // early-exit paths below.
        isForeground = enterForeground(buildListeningNotification())
        if (!isForeground) {
            // Couldn't become foreground (e.g. RECORD_AUDIO not granted, or
            // Android refused a background start). Without it the mic would
            // be muted during calls, so don't pretend to be listening.
            stopSelf()
            return
        }

        audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager

        val resolvedStore = RecordingServiceLocator.preferencesStore
        if (resolvedStore == null) {
            // Should never happen — CallManagerApp wires this at startup.
            // Bail out honestly rather than silently running a no-op service.
            stopSelf()
            return
        }
        store = resolvedStore
        libraryStore = RecordingServiceLocator.libraryStore
        settingsStore = RecordingServiceLocator.settingsStore

        serviceScope.launch {
            resolvedStore.preferences.collect { currentPrefs = it }
        }
        serviceScope.launch {
            settingsStore?.settings?.collect { currentSettings = it }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isForeground || store == null) {
            // onCreate bailed out (see there) — don't keep a half-initialised service alive.
            stopSelf(startId)
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_STOP_RECORDING -> serviceScope.launch {
                finishRecording()
                stopSelf(startId)
            }
            ACTION_CALL_ACTIVE -> {
                if (trackerActive) {
                    // Same call announced again (dual-SIM duplicate broadcast, call
                    // waiting). Every startForegroundService() call expects a
                    // matching startForeground(), so re-assert the notification.
                    sessionStartId = startId
                    refreshForegroundNotification()
                } else {
                    val direction = intent?.getStringExtra(EXTRA_DIRECTION)
                        ?.let { name -> CallDirection.entries.firstOrNull { it.name == name } }
                    if (direction == null) stopSelf(startId) else beginSession(direction, startId)
                }
            }
            // A null/unknown intent is a stale restart of the old always-on
            // service (e.g. right after updating the app) — nothing to do.
            else -> stopSelf(startId)
        }
        // Never restarted by Android on its own: a call that ended while we
        // were dead must not bring a foreground service back from the background.
        return START_NOT_STICKY
    }

    /** Starts watching the call that [CallStateReceiver] just saw go off-hook. */
    private fun beginSession(direction: CallDirection, startId: Int) {
        sessionStartId = startId
        trackerActive = true

        // The tracker only ever sees the call already off-hook; tell it what
        // preceded that so the direction it infers matches the receiver's.
        val assumedPreviousState = when (direction) {
            CallDirection.INCOMING -> TelephonyManager.CALL_STATE_RINGING
            CallDirection.OUTGOING -> TelephonyManager.CALL_STATE_IDLE
        }
        val tracker = CallStateTracker(this, assumedPreviousState)
        callStateTracker = tracker

        val trackerStarted = tracker.start(callbackExecutor) { event ->
            handleCallEvent(event)
        }
        if (!trackerStarted) {
            // READ_PHONE_STATE wasn't actually available when this ran —
            // there's nothing useful this service can do without it. Report
            // it honestly (not as a device-capability problem) and stop.
            trackerActive = false
            RecordingStatusBus.publishEvent(RecordingEvent.PermissionRevoked)
            stopSelf(startId)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (::callStateTracker.isInitialized) callStateTracker.stop()
        if (isCapturing) {
            // Short, bounded cleanup (stop AudioRecord, close the file) —
            // acceptable to block briefly here since onDestroy can't suspend.
            // Wrapped defensively: runBlocking doesn't inherit serviceScope's
            // exception handler, so an uncaught failure here would otherwise
            // crash the app during teardown instead of just failing cleanup.
            try {
                runBlocking { finishRecording() }
            } catch (e: Exception) {
                Log.e(TAG, "Error finishing recording during service teardown", e)
            }
        }
        serviceJob.cancel()
        callbackExecutor.shutdown()
        super.onDestroy()
    }

    private fun handleCallEvent(event: CallEvent) {
        when (event) {
            is CallEvent.Ringing -> Unit // wait for Active — nothing to record yet
            is CallEvent.Active -> {
                callActive = true
                maybeStartRecording(event.direction)
            }
            is CallEvent.Idle -> {
                callActive = false
                // The call is over: finish the recording, then shut everything
                // down. The session is marked over right now so a new call that
                // starts while this one is still being saved gets a fresh session.
                val tracker = callStateTracker
                val sessionId = sessionStartId
                trackerActive = false
                serviceScope.launch {
                    tracker.stop()
                    finishRecording()
                    stopSelf(sessionId)
                }
            }
        }
    }

    private fun maybeStartRecording(direction: CallDirection) {
        val sessionId = sessionStartId
        serviceScope.launch {
            // Read the settings fresh: this service may have been created a few
            // milliseconds ago, before the collectors in onCreate emitted anything.
            store?.preferences?.first()?.let { currentPrefs = it }
            settingsStore?.settings?.first()?.let { currentSettings = it }

            val directionEnabled = currentPrefs.masterEnabled && when (direction) {
                CallDirection.INCOMING -> currentPrefs.incomingEnabled
                CallDirection.OUTGOING -> currentPrefs.outgoingEnabled
            }
            if (!directionEnabled) {
                stopSelf(sessionId) // turned off since the receiver looked — nothing to do
                return@launch
            }

            awaitShizukuIfInstalled()
            startRecording(direction, sessionId)
        }
    }

    /**
     * On a cold start (the process was created by the call broadcast) Shizuku's
     * binder and recorder service connect asynchronously. Give them a short
     * moment before falling back to the in-app recorder — which, started from
     * the background, is the less reliable path. Skipped entirely when Shizuku
     * isn't installed, and returns early the moment it is ready.
     */
    private suspend fun awaitShizukuIfInstalled() {
        if (ShizukuBridge.isReady() || !ShizukuBridge.isInstalled(this)) return
        withTimeoutOrNull(SHIZUKU_WAIT_MILLIS) {
            ShizukuBridge.state.first { it == ShizukuState.READY }
        }
    }

    private suspend fun startRecording(direction: CallDirection, sessionId: Int) =
        lifecycleMutex.withLock {
            // Both checks happen under the lock, so they can't go stale
            // between "looked" and "started".
            if (isCapturing) return@withLock // already recording this call
            if (!callActive) return@withLock // the call already ended before we got here

            // No early exit on a cached INIT_UNSUPPORTED: earlier versions
            // could persist it after one transient mic failure, and trusting
            // it would keep a working device blocked forever. Just try — the
            // recorder reports honestly if nothing can start.
            val outputFile = createOutputFile(direction)
            val startedAt = System.currentTimeMillis()

            // Already foreground since onCreate — this just swaps the
            // startup notification for the "Recording active" one. If
            // the swap fails the recording carries on regardless.
            enterForeground(buildRecordingNotification(startedAt))

            // Shizuku first (real call-audio tap), in-app recorder as the fallback.
            val shizukuAttempt = if (ShizukuBridge.isReady()) shizukuRecorder.start(outputFile) else null
            val shizukuFailure: String? =
                if (shizukuAttempt != null && shizukuAttempt !is CallAudioRecorder.StartResult.Started) {
                    shizukuRecorder.lastError ?: "unknown error"
                } else {
                    null
                }
            usingShizuku = shizukuAttempt is CallAudioRecorder.StartResult.Started
            val startResult: CallAudioRecorder.StartResult =
                if (shizukuAttempt is CallAudioRecorder.StartResult.Started) {
                    shizukuAttempt
                } else {
                    audioRecorder.start(outputFile, serviceScope)
                }

            when (startResult) {
                is CallAudioRecorder.StartResult.Started -> {
                    currentDirection = direction
                    currentOutputFile = outputFile
                    recordingStartedAtMillis = startedAt
                    // The speaker trick is only a workaround for the in-app recorder.
                    if (!usingShizuku) startSpeakerOverrideIfEnabled()
                    RecordingStatusBus.publishLiveState(
                        RecordingLiveState.Recording(direction, startedAt)
                    )
                    // Shizuku was tried and failed, but the in-app fallback started: say why.
                    if (shizukuFailure != null) {
                        RecordingStatusBus.publishEvent(RecordingEvent.ShizukuFailed(shizukuFailure))
                    }
                }
                is CallAudioRecorder.StartResult.AllSourcesFailed -> {
                    refreshForegroundNotification()
                    // Deliberately NOT persisted as INIT_UNSUPPORTED: a live
                    // call can fail to open the mic for transient reasons
                    // (another app holding it, audio focus race). Saving that
                    // would disable recording permanently until the user
                    // re-toggled it. The call-independent probe in Settings/
                    // Recording is what decides real device support.
                    RecordingStatusBus.publishEvent(
                        if (shizukuFailure != null) RecordingEvent.ShizukuFailed(shizukuFailure)
                        else RecordingEvent.DeviceUnsupported
                    )
                    stopSelf(sessionId) // nothing is being recorded — don't linger for the rest of the call
                }
                is CallAudioRecorder.StartResult.PermissionDenied -> {
                    refreshForegroundNotification()
                    // RECORD_AUDIO got revoked after being granted — this is
                    // not a device limitation, so the persisted capability is
                    // deliberately left untouched.
                    RecordingStatusBus.publishEvent(RecordingEvent.PermissionRevoked)
                    stopSelf(sessionId)
                }
            }
        }

    /** Promotes (or updates) the foreground notification. Returns false instead of throwing if Android refuses. */
    private fun enterForeground(notification: Notification): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            // The microphone service type only exists from API 30; on Android 10
            // the plain call is the right one (and passing the type would throw).
            startForeground(NOTIFICATION_ID, notification)
        }
        true
    } catch (e: Exception) {
        Log.w(TAG, "startForeground was refused", e)
        false
    }

    /** Shows "Recording active" while a recording is running, the plain listening notification otherwise. */
    private fun refreshForegroundNotification() {
        val notification = if (isCapturing) {
            buildRecordingNotification(recordingStartedAtMillis)
        } else {
            buildListeningNotification()
        }
        enterForeground(notification)
    }

    /**
     * Re-asserts MODE_IN_CALL + speakerphone-on every 500ms (some OEMs
     * silently revert one or both if nothing keeps re-applying them — see
     * SW Call Recorder's RecorderService.putSpeakerOn(), which this
     * mirrors) for as long as the job stays alive. Cancelling it — via
     * [stopSpeakerOverride] — always restores MODE_NORMAL and speaker-off
     * in the `finally`, so a cancelled or crashed override never leaves
     * the call stuck on speaker.
     */
    private fun startSpeakerOverrideIfEnabled() {
        if (!currentSettings.forceSpeakerphoneWhileRecording) return
        val manager = audioManager ?: return
        speakerOverrideJob = serviceScope.launch {
            try {
                while (isActive) {
                    manager.mode = AudioManager.MODE_IN_CALL
                    if (!manager.isSpeakerphoneOn) manager.isSpeakerphoneOn = true
                    delay(500)
                }
            } finally {
                manager.isSpeakerphoneOn = false
                manager.mode = AudioManager.MODE_NORMAL
            }
        }
    }

    /** Waits for the override job's own restore-audio-state cleanup to actually finish, not just for cancellation to be requested. */
    private suspend fun stopSpeakerOverride() {
        speakerOverrideJob?.cancelAndJoin()
        speakerOverrideJob = null
    }

    /** Guarded by [lifecycleMutex] so a call-ending event and a Stop-button tap can't both race through this at once. */
    private suspend fun finishRecording() = lifecycleMutex.withLock {
        stopSpeakerOverride()
        if (!isCapturing) return@withLock

        val viaShizuku = usingShizuku
        val avgRms = if (viaShizuku) shizukuRecorder.stop() else audioRecorder.stop()
        usingShizuku = false
        val direction = currentDirection
        val outputFile = currentOutputFile
        val durationMillis = (System.currentTimeMillis() - recordingStartedAtMillis).coerceAtLeast(0)

        // No notification swap any more: the caller stops the service as soon
        // as this returns, which removes the notification.
        RecordingStatusBus.publishLiveState(RecordingLiveState.Idle)

        currentDirection = null
        currentOutputFile = null

        if (direction == null || outputFile == null) return@withLock

        val isSilent = PcmAmplitudeAnalyzer.isSilent(avgRms)
        updateCapabilityAfterCall(isSilent, durationMillis, viaShizuku)

        libraryStore?.addRecording(
            RecordingLibraryItem(
                id = 0,
                filePath = outputFile.absolutePath,
                fileName = outputFile.name,
                direction = direction,
                createdAtMillis = recordingStartedAtMillis,
                durationMillis = durationMillis,
                isSilent = isSilent
            )
        )

        libraryStore?.let { RecordingMaintenance(it).applyRetentionPolicies(currentSettings) }

        folderCopier.copyIfConfigured(outputFile, currentSettings.customFolderUri)

        if (currentSettings.notifyOnFinish) {
            notifyRecordingResult(isSilent)
        }

        RecordingStatusBus.publishEvent(
            if (isSilent) RecordingEvent.SavedButSilent(outputFile.name)
            else RecordingEvent.Saved(outputFile.name)
        )
    }

    /**
     * One call is weak evidence, so this is deliberately asymmetric:
     * - real audio → SUPPORTED (definitive: it demonstrably works);
     * - silence → SILENT only if it can actually mean "the device can't do
     *   it": the recording ran in the foreground, lasted long enough to
     *   contain speech, and the device wasn't already confirmed working.
     *   A quiet/very short call, or a background start that Android muted,
     *   must not flip a working device to "unavailable".
     *
     * Every recording is now started from the background (by the call
     * broadcast), where Android may mute the in-app microphone — so silence
     * only counts as evidence against the device when it came through
     * Shizuku (shell process, not subject to that restriction).
     */
    private suspend fun updateCapabilityAfterCall(isSilent: Boolean, durationMillis: Long, viaShizuku: Boolean) {
        val prefsStore = store ?: return
        if (!isSilent) {
            prefsStore.setCapability(RecordingCapability.SUPPORTED)
            return
        }
        if (!isForeground || !viaShizuku || durationMillis < MIN_DURATION_FOR_SILENT_VERDICT_MILLIS) return
        if (prefsStore.capability.first() == RecordingCapability.SUPPORTED) return
        prefsStore.setCapability(RecordingCapability.SILENT)
    }

    /** A dismissible, one-shot notification — separate from the ongoing "recording active" one — so an outcome is visible even if the app isn't open when the call ends. */
    private fun notifyRecordingResult(isSilent: Boolean) {
        val title = if (isSilent) {
            getString(R.string.recording_result_silent_title)
        } else {
            getString(R.string.recording_result_saved_title)
        }
        val notification = NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(R.drawable.ic_notification_recording)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            // Unique-ish ID per result so consecutive calls don't overwrite each other's notification.
            NotificationManagerCompat.from(this).notify(System.currentTimeMillis().toInt(), notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted — nothing more we can honestly do here.
        }
    }

    private fun createOutputFile(direction: CallDirection): File {
        val dir = RecordingStorage.recordingsDir(this)
        dir.mkdirs()
        return File(dir, RecordingFileNaming.buildFileName(direction, currentSettings.includeDirectionInFileName))
    }

    private fun buildListeningNotification(): Notification =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.recording_notification_title))
            .setContentText(getString(R.string.recording_listening_text))
            .setSmallIcon(R.drawable.ic_notification_recording)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun buildRecordingNotification(startedAtMillis: Long): Notification {
        val stopIntent = Intent(this, CallRecordingService::class.java).apply {
            action = ACTION_STOP_RECORDING
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.recording_notification_title))
            .setContentText(getString(R.string.recording_notification_text))
            .setSmallIcon(R.drawable.ic_notification_recording)
            .setOngoing(true)
            .setUsesChronometer(true)
            .setWhen(startedAtMillis)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(R.string.recording_notification_stop), stopPendingIntent)
            .build()
    }

    private fun createNotificationChannels() {
        val ongoingChannel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.recording_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.recording_notification_channel_description)
        }
        val resultChannel = NotificationChannel(
            RESULT_CHANNEL_ID,
            getString(R.string.recording_result_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.recording_result_channel_description)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(ongoingChannel)
        manager.createNotificationChannel(resultChannel)
    }

    companion object {
        private const val TAG = "CallRecordingService"
        const val ACTION_STOP_RECORDING = "com.saimum.callmanager.recording.ACTION_STOP_RECORDING"
        private const val ACTION_CALL_ACTIVE = "com.saimum.callmanager.recording.ACTION_CALL_ACTIVE"
        private const val EXTRA_DIRECTION = "direction"
        private const val NOTIFICATION_CHANNEL_ID = "call_recording"
        private const val RESULT_CHANNEL_ID = "call_recording_result"
        private const val NOTIFICATION_ID = 1001
        private const val MIN_DURATION_FOR_SILENT_VERDICT_MILLIS = 5_000L
        private const val SHIZUKU_WAIT_MILLIS = 2_500L

        /**
         * Called by [CallStateReceiver] when a call has just gone off-hook.
         * Starts the short-lived recording service for that one call. Returns
         * false (and logs) if Android refuses a background foreground-service
         * start — then this call simply isn't recorded. Never throws.
         */
        fun startForCall(context: Context, direction: CallDirection): Boolean = try {
            val intent = Intent(context, CallRecordingService::class.java).apply {
                action = ACTION_CALL_ACTIVE
                putExtra(EXTRA_DIRECTION, direction.name)
            }
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (Android 12+) lands here.
            Log.w(TAG, "Android refused to start the recording service for this call", e)
            false
        }

        /**
         * "Listening" is now just "the call receiver is enabled" — no service
         * runs. Idempotent and safe to call from anywhere, any time (also from
         * the background). Name kept so existing callers are unchanged.
         */
        fun startListening(context: Context) {
            CallStateReceiver.setEnabled(context, true)
        }

        /** Master toggle off: stop detecting calls entirely, and end any recording in progress. */
        fun stopListening(context: Context) {
            CallStateReceiver.setEnabled(context, false)
            context.stopService(Intent(context, CallRecordingService::class.java))
        }

        /** Requests the current in-progress recording (if any) stop now. */
        fun requestStopRecording(context: Context) {
            val intent = Intent(context, CallRecordingService::class.java).apply {
                action = ACTION_STOP_RECORDING
            }
            context.startService(intent)
        }
    }
}
