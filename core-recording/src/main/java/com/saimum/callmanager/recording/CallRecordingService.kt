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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
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

/**
 * Owns the real call-recording lifecycle:
 * - watches call state via [CallStateTracker]
 * - starts/stops [CallAudioRecorder] against real calls only
 * - reports honest outcomes via [RecordingStatusBus]: a silent capture is
 *   reported as silent, not as a generic success
 *
 * Lifecycle: while the master toggle is on, this runs as a *foreground*
 * service (microphone type) with a low-priority "Call recording is on"
 * notification, started while the app is on screen. That is deliberate —
 * a plain background service is stopped by Android soon after the app
 * leaves the screen, and from Android 11 on a service that wasn't promoted
 * to foreground *from the foreground* gets silence when it opens the
 * microphone, and can't be promoted later from the background (Android 12+
 * background-start limits). Being foreground before any call arrives is
 * what lets the microphone stay usable when a call starts. While a call is
 * being recorded the same notification switches to "Recording active" with
 * a Stop button, then goes back to the listening one.
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
        // startForegroundService(), which gives us only a few seconds to
        // call startForeground() — including on the early-exit paths below.
        isForeground = enterForeground(buildListeningNotification())
        if (!isForeground) {
            // Couldn't become foreground (e.g. RECORD_AUDIO not granted, or
            // Android refused a background start). Without it the mic would
            // be muted during calls, so don't pretend to be listening.
            stopSelf()
            return
        }

        callStateTracker = CallStateTracker(this)
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

        val trackerStarted = callStateTracker.start(callbackExecutor) { event ->
            handleCallEvent(event)
        }
        if (!trackerStarted) {
            // READ_PHONE_STATE wasn't actually available when this ran —
            // there's nothing useful this service can do without it. Report
            // it honestly (not as a device-capability problem) and stop.
            RecordingStatusBus.publishEvent(RecordingEvent.PermissionRevoked)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_RECORDING) {
            serviceScope.launch { finishRecording() }
        } else if (isForeground) {
            // Every startForegroundService() call expects a matching
            // startForeground(); re-assert whichever notification is current
            // so a repeated startListening() can't leave one unanswered.
            refreshForegroundNotification()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (::callStateTracker.isInitialized) callStateTracker.stop()
        if (audioRecorder.isRecording) {
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
                serviceScope.launch { finishRecording() }
            }
        }
    }

    private fun maybeStartRecording(direction: CallDirection) {
        if (!currentPrefs.masterEnabled) return

        val directionEnabled = when (direction) {
            CallDirection.INCOMING -> currentPrefs.incomingEnabled
            CallDirection.OUTGOING -> currentPrefs.outgoingEnabled
        }
        if (!directionEnabled) return

        serviceScope.launch { startRecording(direction) }
    }

    private suspend fun startRecording(direction: CallDirection) =
        lifecycleMutex.withLock {
            // Both checks happen under the lock, so they can't go stale
            // between "looked" and "started".
            if (audioRecorder.isRecording) return@withLock // already recording this call
            if (!callActive) return@withLock // the call already ended before we got here

            // No early exit on a cached INIT_UNSUPPORTED: earlier versions
            // could persist it after one transient mic failure, and trusting
            // it would keep a working device blocked forever. Just try — the
            // recorder reports honestly if nothing can start.
            val outputFile = createOutputFile(direction)
            val startedAt = System.currentTimeMillis()

            // Already foreground since onCreate — this just swaps the
            // "listening" notification for the "Recording active" one. If
            // the swap fails the recording carries on regardless.
            enterForeground(buildRecordingNotification(startedAt))

            when (audioRecorder.start(outputFile, serviceScope)) {
                is CallAudioRecorder.StartResult.Started -> {
                    currentDirection = direction
                    currentOutputFile = outputFile
                    recordingStartedAtMillis = startedAt
                    startSpeakerOverrideIfEnabled()
                    RecordingStatusBus.publishLiveState(
                        RecordingLiveState.Recording(direction, startedAt)
                    )
                }
                is CallAudioRecorder.StartResult.AllSourcesFailed -> {
                    refreshForegroundNotification()
                    // Deliberately NOT persisted as INIT_UNSUPPORTED: a live
                    // call can fail to open the mic for transient reasons
                    // (another app holding it, audio focus race). Saving that
                    // would disable recording permanently until the user
                    // re-toggled it. The call-independent probe in Settings/
                    // Recording is what decides real device support.
                    RecordingStatusBus.publishEvent(RecordingEvent.DeviceUnsupported)
                }
                is CallAudioRecorder.StartResult.PermissionDenied -> {
                    refreshForegroundNotification()
                    // RECORD_AUDIO got revoked after being granted — this is
                    // not a device limitation, so the persisted capability is
                    // deliberately left untouched.
                    RecordingStatusBus.publishEvent(RecordingEvent.PermissionRevoked)
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
        val notification = if (audioRecorder.isRecording) {
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
        if (!audioRecorder.isRecording) return@withLock

        val avgRms = audioRecorder.stop()
        val direction = currentDirection
        val outputFile = currentOutputFile
        val durationMillis = (System.currentTimeMillis() - recordingStartedAtMillis).coerceAtLeast(0)

        // Back to the quiet "listening" notification — the service itself
        // stays foreground for as long as the master toggle is on.
        enterForeground(buildListeningNotification())
        RecordingStatusBus.publishLiveState(RecordingLiveState.Idle)

        currentDirection = null
        currentOutputFile = null

        if (direction == null || outputFile == null) return@withLock

        val isSilent = PcmAmplitudeAnalyzer.isSilent(avgRms)
        updateCapabilityAfterCall(isSilent, durationMillis)

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
     */
    private suspend fun updateCapabilityAfterCall(isSilent: Boolean, durationMillis: Long) {
        val prefsStore = store ?: return
        if (!isSilent) {
            prefsStore.setCapability(RecordingCapability.SUPPORTED)
            return
        }
        if (!isForeground || durationMillis < MIN_DURATION_FOR_SILENT_VERDICT_MILLIS) return
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
        private const val NOTIFICATION_CHANNEL_ID = "call_recording"
        private const val RESULT_CHANNEL_ID = "call_recording_result"
        private const val NOTIFICATION_ID = 1001
        private const val MIN_DURATION_FOR_SILENT_VERDICT_MILLIS = 5_000L

        /**
         * Starts the service listening for calls. Safe to call repeatedly.
         * Call it only while the app is on screen (toggle turned on, or
         * MainActivity starting) — Android won't let a foreground service
         * be started from the background. Never throws.
         */
        fun startListening(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, CallRecordingService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't start the call-listening service", e)
            }
        }

        /** Stops listening entirely (user turned the master toggle off). */
        fun stopListening(context: Context) {
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
