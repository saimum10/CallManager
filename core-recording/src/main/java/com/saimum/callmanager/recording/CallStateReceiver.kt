package com.saimum.callmanager.recording

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The only always-registered piece of call detection. Declared in the
 * manifest for `android.intent.action.PHONE_STATE`, so Android itself wakes
 * the app (even when the UI is closed and the process is dead) when the
 * phone changes state — nothing has to be running in between.
 *
 * Job: when a call goes off-hook (incoming answered, or outgoing dialed),
 * work out the direction, check cheaply that recording is wanted for that
 * direction, and only then start [CallRecordingService]. The service owns
 * everything else (tracking the call end, recording, saving) and stops
 * itself when the call is over.
 *
 * Direction is inferred exactly as before — RINGING → OFFHOOK is incoming,
 * IDLE → OFFHOOK is outgoing — but a manifest receiver is a fresh object per
 * broadcast (and may run in a freshly started process), so the previous
 * state is kept in a tiny SharedPreferences file instead of a field.
 *
 * `android.intent.action.PHONE_STATE` is a protected broadcast (only the
 * system can send it), so `exported="true"` does not let other apps spoof it.
 */
class CallStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
            TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
            TelephonyManager.EXTRA_STATE_IDLE -> TelephonyManager.CALL_STATE_IDLE
            else -> return
        }

        val appContext = context.applicationContext
        // Non-null only for the transition into an active call. RINGING and
        // IDLE just update the remembered state: the service (when running)
        // watches for the call end itself, and there is nothing to start.
        val direction = recordTransition(appContext, state) ?: return

        // Reading DataStore is a disk read — keep it off the main thread and
        // hold the broadcast open (goAsync) until we've decided.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler).launch {
            try {
                if (shouldRecord(appContext, direction)) {
                    CallRecordingService.startForCall(appContext, direction)
                }
            } finally {
                pending.finish()
            }
        }
    }

    /** Remembers [state] and returns the call direction if this is the moment a call became active. */
    private fun recordTransition(context: Context, state: Int): CallDirection? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastState = prefs.getInt(KEY_STATE, TelephonyManager.CALL_STATE_IDLE)
        val lastAt = prefs.getLong(KEY_AT_ELAPSED, 0L)
        val lastDirection = prefs.getString(KEY_DIRECTION, null)
            ?.let { name -> CallDirection.entries.firstOrNull { it.name == name } }

        val now = SystemClock.elapsedRealtime()
        // elapsedRealtime restarts at boot, so a negative gap means the stored
        // state is from before a reboot and must not be trusted.
        val sinceLast = now - lastAt

        val direction: CallDirection? = if (state != TelephonyManager.CALL_STATE_OFFHOOK) {
            null
        } else {
            when {
                // A repeated OFFHOOK (dual-SIM duplicate, call-waiting swap): same call, same direction.
                lastState == TelephonyManager.CALL_STATE_OFFHOOK && sinceLast >= 0 && lastDirection != null ->
                    lastDirection
                // Was ringing a moment ago → the incoming call was just answered.
                lastState == TelephonyManager.CALL_STATE_RINGING && sinceLast in 0..MAX_RING_MILLIS ->
                    CallDirection.INCOMING
                else -> CallDirection.OUTGOING
            }
        }

        prefs.edit().apply {
            putInt(KEY_STATE, state)
            putLong(KEY_AT_ELAPSED, now)
            if (direction != null) putString(KEY_DIRECTION, direction.name)
        }.apply()

        return direction
    }

    /** Cheap gate so the service (and its notification) is only started for calls that will actually be recorded. */
    private suspend fun shouldRecord(context: Context, direction: CallDirection): Boolean {
        val prefs = RecordingServiceLocator.preferencesStore?.preferences?.first() ?: return false
        if (!prefs.masterEnabled) return false
        val directionEnabled = when (direction) {
            CallDirection.INCOMING -> prefs.incomingEnabled
            CallDirection.OUTGOING -> prefs.outgoingEnabled
        }
        if (!directionEnabled) return false
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "CallStateReceiver"
        private const val PREFS_NAME = "call_state_receiver"
        private const val KEY_STATE = "last_state"
        private const val KEY_AT_ELAPSED = "last_state_at"
        private const val KEY_DIRECTION = "last_direction"

        /** An unanswered ring never lasts this long; older RINGING is stale (e.g. phone died mid-ring). */
        private const val MAX_RING_MILLIS = 3 * 60 * 1000L

        private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            Log.e(TAG, "Failed while deciding whether to record this call", throwable)
        }

        /**
         * Turns call detection on or off at the OS level. Disabled, this
         * receiver is never woken at all — zero cost when the master toggle
         * is off. Enabled is also the manifest default, so an app update
         * never silently stops detection for someone who already has it on.
         */
        fun setEnabled(context: Context, enabled: Boolean) {
            try {
                context.packageManager.setComponentEnabledSetting(
                    ComponentName(context, CallStateReceiver::class.java),
                    if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't change the call-detection receiver state", e)
            }
        }
    }
}
