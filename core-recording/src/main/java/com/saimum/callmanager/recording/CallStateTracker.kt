package com.saimum.callmanager.recording

import android.content.Context
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor

/**
 * Watches the device's call state and emits [CallEvent]s with direction
 * already inferred.
 *
 * Note on scope: this tracks the default voice subscription's call state,
 * not per-SIM state. Fine-grained dual-SIM distinction matters far more
 * for call forwarding (Phase 3) than for "is a call active right now,
 * and which way" — which is all the recording engine needs.
 *
 * Caller must hold READ_PHONE_STATE before calling [start] — but [start]
 * still guards the actual registration call with a try/catch, since
 * permission can be revoked in the gap between the caller's check and this
 * call actually running, and an uncaught SecurityException here would
 * crash the whole service.
 */
class CallStateTracker(context: Context) {

    private val telephonyManager: TelephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

    private var previousState: Int = TelephonyManager.CALL_STATE_IDLE
    private var legacyListener: PhoneStateListener? = null
    private var modernCallback: TelephonyCallback? = null

    /** Returns false if registration failed (most likely READ_PHONE_STATE isn't actually granted) — never throws. */
    fun start(mainExecutor: Executor, onEvent: (CallEvent) -> Unit): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startModern(mainExecutor, onEvent)
            } else {
                startLegacy(onEvent)
            }
            true
        } catch (e: SecurityException) {
            legacyListener = null
            modernCallback = null
            false
        }
    }

    fun stop() {
        legacyListener?.let {
            try {
                @Suppress("DEPRECATION")
                telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE)
            } catch (e: SecurityException) {
                // Permission already gone — nothing to unregister from the OS's point of view.
            }
        }
        legacyListener = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            modernCallback?.let {
                try {
                    telephonyManager.unregisterTelephonyCallback(it)
                } catch (e: SecurityException) {
                    // Same as above.
                }
            }
        }
        modernCallback = null
    }

    private fun handleStateChange(state: Int, onEvent: (CallEvent) -> Unit) {
        val event = when (state) {
            TelephonyManager.CALL_STATE_RINGING -> CallEvent.Ringing
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                // Coming from RINGING means the incoming call was just
                // answered. Coming from anywhere else (usually IDLE) means
                // this device just dialed out.
                val direction = if (previousState == TelephonyManager.CALL_STATE_RINGING) {
                    CallDirection.INCOMING
                } else {
                    CallDirection.OUTGOING
                }
                CallEvent.Active(direction)
            }
            else -> CallEvent.Idle
        }
        previousState = state

        // This callback runs on a raw Executor thread (see start()), not a
        // coroutine — CallRecordingService's CoroutineExceptionHandler can't
        // reach an exception thrown directly here. Guarded independently so
        // a bug in the caller's synchronous event-handling path can't crash
        // the app from a background thread with zero safety net.
        try {
            onEvent(event)
        } catch (e: Exception) {
            Log.e("CallStateTracker", "Unhandled error while dispatching a call-state event", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun startLegacy(onEvent: (CallEvent) -> Unit) {
        val listener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                handleStateChange(state, onEvent)
            }
        }
        // Registered only after a successful listen() call below returns
        // without throwing — see the try/catch in start().
        telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        legacyListener = listener
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun startModern(mainExecutor: Executor, onEvent: (CallEvent) -> Unit) {
        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                handleStateChange(state, onEvent)
            }
        }
        telephonyManager.registerTelephonyCallback(mainExecutor, callback)
        modernCallback = callback
    }
}
