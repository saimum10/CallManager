package com.saimum.callmanager.telephony

import java.util.Locale

/**
 * Best-effort reading of the carrier's free-text reply to a forwarding
 * request.
 *
 * Why this exists: [ForwardingDialer] reports success whenever the carrier
 * *answers* — but carriers deliver rejections ("Connection problem or
 * invalid MMI code", "Service not supported"…) through the very same
 * response callback as confirmations. Treating every answer as "forwarding
 * is now on" would save a wrong state whenever the network said no.
 *
 * This is a heuristic on English wording, not a guarantee: a rejection in
 * another language won't be recognised here. That's why the UI still labels
 * the state as not carrier-verified, and why unconditional forwarding also
 * has the real network indicator ([CallForwardingIndicatorTracker]).
 */
object CarrierResponseInterpreter {

    private val failureMarkers = listOf(
        "error",
        "fail",
        "invalid",
        "not supported",
        "not allowed",
        "not available",
        "unavailable",
        "unable",
        "reject",
        "denied",
        "problem",
        "unsuccessful",
        "not subscribed",
        "barred",
        "restricted",
        "try again"
    )

    fun looksLikeFailure(response: String): Boolean {
        val text = response.lowercase(Locale.ROOT)
        return failureMarkers.any { it in text }
    }
}
