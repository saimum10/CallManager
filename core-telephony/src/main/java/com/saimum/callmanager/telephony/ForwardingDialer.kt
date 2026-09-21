package com.saimum.callmanager.telephony

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

sealed class ForwardingDialResult {
    /** The carrier's actual response text — e.g. "Call forwarding activated" or similar, in whatever form/language the network sends. */
    data class Success(val carrierResponse: String) : ForwardingDialResult()
    data class Failed(val reason: String) : ForwardingDialResult()
    data object PermissionMissing : ForwardingDialResult()
}

/**
 * Sends standard MMI/USSD codes (built by [ForwardingMmiCodes]) via
 * [TelephonyManager.sendUssdRequest] — deliberately NOT
 * `TelecomManager.placeCall()`.
 *
 * Why this matters: `placeCall()` with an MMI-pattern string gets routed
 * through the system's default dialer UI for confirmation — an OS-level
 * behavior outside this app's control, confirmed by real on-device
 * testing. `sendUssdRequest()` is the correct, documented API for sending
 * supplementary-service control strings silently, and unlike
 * `placeCall()`, its callback returns the carrier's *actual* response
 * text — real confirmation, not a guess.
 */
class ForwardingDialer(private val context: Context) {

    @SuppressLint("MissingPermission")
    suspend fun dial(mmiCode: String, subscriptionId: Int): ForwardingDialResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return ForwardingDialResult.PermissionMissing
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // sendUssdRequest requires API 26+. This app's minSdk is 29, so
            // this branch is unreachable in practice — kept as an honest
            // guard rather than assuming.
            return ForwardingDialResult.Failed("This Android version doesn't support silent forwarding requests")
        }

        return try {
            val result = withTimeoutOrNull(REQUEST_TIMEOUT_MILLIS) {
                suspendCancellableCoroutine { continuation ->
                    val telephonyManager =
                        (context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager)
                            .createForSubscriptionId(subscriptionId)

                    val callback = object : TelephonyManager.UssdResponseCallback() {
                        override fun onReceiveUssdResponse(
                            telephonyManager: TelephonyManager,
                            request: String,
                            response: CharSequence
                        ) {
                            if (continuation.isActive) {
                                continuation.resume(ForwardingDialResult.Success(response.toString()))
                            }
                        }

                        override fun onReceiveUssdResponseFailed(
                            telephonyManager: TelephonyManager,
                            request: String,
                            failureCode: Int
                        ) {
                            if (continuation.isActive) {
                                continuation.resume(ForwardingDialResult.Failed(describeFailure(failureCode)))
                            }
                        }
                    }

                    try {
                        telephonyManager.sendUssdRequest(mmiCode, callback, Handler(Looper.getMainLooper()))
                    } catch (e: SecurityException) {
                        if (continuation.isActive) {
                            continuation.resume(ForwardingDialResult.PermissionMissing)
                        }
                    }
                }
            }
            result ?: ForwardingDialResult.Failed("The carrier didn't respond in time")
        } catch (e: SecurityException) {
            ForwardingDialResult.PermissionMissing
        } catch (e: Exception) {
            ForwardingDialResult.Failed(e.message ?: "Unable to send the request")
        }
    }

    private fun describeFailure(failureCode: Int): String = when (failureCode) {
        TelephonyManager.USSD_RETURN_FAILURE -> "The carrier rejected this request"
        TelephonyManager.USSD_ERROR_SERVICE_UNAVAIL -> "USSD service is unavailable right now"
        else -> "Request failed (code $failureCode)"
    }

    private companion object {
        const val REQUEST_TIMEOUT_MILLIS = 30_000L
    }
}
