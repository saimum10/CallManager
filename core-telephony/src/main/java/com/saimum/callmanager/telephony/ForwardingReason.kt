package com.saimum.callmanager.telephony

/**
 * The forwarding conditions a user can configure per SIM.
 * Real MMI-code mapping (e.g. *21*, *67*, *61*, *62*) and the
 * "locally tracked, not carrier-verified" state model land in the
 * call-forwarding phase — see the project notes on why live carrier
 * status can't be read by a normal app.
 */
enum class ForwardingReason {
    UNCONDITIONAL,
    WHEN_BUSY,
    WHEN_UNANSWERED,
    WHEN_UNREACHABLE
}
