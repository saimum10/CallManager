package com.saimum.callmanager.telephony

data class ForwardingConfig(
    val enabled: Boolean = false,
    val reason: ForwardingReason = ForwardingReason.UNCONDITIONAL,
    val destinationNumber: String = ""
)
