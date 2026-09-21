package com.saimum.callmanager.telephony

/**
 * Builds the standard 3GPP/GSM supplementary-service MMI codes for call
 * forwarding — the same codes any phone's built-in dialer/settings uses
 * (e.g. dialing **21*+8801XXXXXXXXX# to turn on unconditional forwarding).
 * Nothing proprietary or carrier-specific here.
 */
object ForwardingMmiCodes {

    /** E.164 allows at most 15 digits (not counting the leading "+"). */
    const val MAX_DESTINATION_DIGITS = 15

    /**
     * Keeps only what can safely go inside an MMI string: an optional
     * leading "+" (international format) followed by digits, at most
     * [MAX_DESTINATION_DIGITS] of them. Anything else — notably "*" and
     * "#", which are MMI syntax — is dropped, so a number can never change
     * the meaning of the code it's embedded in.
     */
    fun sanitizeDestination(raw: String): String {
        val digits = raw.filter { it.isDigit() }.take(MAX_DESTINATION_DIGITS)
        return if (raw.trimStart().startsWith("+")) "+$digits" else digits
    }

    fun activationCode(reason: ForwardingReason, destinationNumber: String): String =
        "**${prefixFor(reason)}*${sanitizeDestination(destinationNumber)}#"

    fun deactivationCode(reason: ForwardingReason): String =
        "##${prefixFor(reason)}#"

    /** Asks the carrier for the current real status of this forwarding type — the response is free text, not structured. */
    fun interrogationCode(reason: ForwardingReason): String =
        "*#${prefixFor(reason)}#"

    private fun prefixFor(reason: ForwardingReason): String = when (reason) {
        ForwardingReason.UNCONDITIONAL -> "21"
        ForwardingReason.WHEN_BUSY -> "67"
        ForwardingReason.WHEN_UNANSWERED -> "61"
        ForwardingReason.WHEN_UNREACHABLE -> "62"
    }
}
