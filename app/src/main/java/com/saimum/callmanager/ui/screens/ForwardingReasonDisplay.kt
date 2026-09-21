package com.saimum.callmanager.ui.screens

import com.saimum.callmanager.telephony.ForwardingReason

fun ForwardingReason.displayLabel(): String = when (this) {
    ForwardingReason.UNCONDITIONAL -> "Unconditional"
    ForwardingReason.WHEN_BUSY -> "When busy"
    ForwardingReason.WHEN_UNANSWERED -> "When unanswered"
    ForwardingReason.WHEN_UNREACHABLE -> "When unreachable"
}
