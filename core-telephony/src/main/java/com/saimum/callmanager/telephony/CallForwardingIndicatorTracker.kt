package com.saimum.callmanager.telephony

import android.content.Context
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import java.util.concurrent.Executor

/**
 * Watches the network's real Call Forwarding Indicator (CFI) for one SIM —
 * the same signal a phone's status bar uses to show a small icon when
 * unconditional forwarding is active. Requires only READ_PHONE_STATE, no
 * privileged permission — this corrects an earlier, incomplete claim that
 * live carrier-verified forwarding status wasn't obtainable at all.
 *
 * Scope: this only covers UNCONDITIONAL forwarding. The network doesn't
 * expose an equivalent live boolean for busy/no-answer/unreachable
 * forwarding — those need an interrogation USSD request instead (see
 * ForwardingDialer.dial with ForwardingMmiCodes.interrogationCode), whose
 * result is free text, not a structured signal.
 *
 * No persistent background service by design — start() when the
 * Forwarding screen becomes visible, stop() when it's left.
 */
class CallForwardingIndicatorTracker(context: Context, subscriptionId: Int) {

    private val telephonyManager: TelephonyManager =
        (context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager)
            .createForSubscriptionId(subscriptionId)

    private var legacyListener: PhoneStateListener? = null
    private var modernCallback: TelephonyCallback? = null

    /** Returns false if registration failed (most likely READ_PHONE_STATE isn't granted) — never throws. */
    fun start(executor: Executor, onIndicatorChanged: (Boolean) -> Unit): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                startModern(executor, onIndicatorChanged)
            } else {
                startLegacy(onIndicatorChanged)
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

    @Suppress("DEPRECATION")
    private fun startLegacy(onIndicatorChanged: (Boolean) -> Unit) {
        val listener = object : PhoneStateListener() {
            @Suppress("DEPRECATION")
            override fun onCallForwardingIndicatorChanged(cfi: Boolean) {
                onIndicatorChanged(cfi)
            }
        }
        telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_FORWARDING_INDICATOR)
        legacyListener = listener
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun startModern(executor: Executor, onIndicatorChanged: (Boolean) -> Unit) {
        val callback = object : TelephonyCallback(), TelephonyCallback.CallForwardingIndicatorListener {
            override fun onCallForwardingIndicatorChanged(cfi: Boolean) {
                onIndicatorChanged(cfi)
            }
        }
        telephonyManager.registerTelephonyCallback(executor, callback)
        modernCallback = callback
    }
}
