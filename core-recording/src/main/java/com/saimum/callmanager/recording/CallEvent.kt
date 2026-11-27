package com.saimum.callmanager.recording

/**
 * Call state events, already resolved to a direction where we have one.
 *
 * Direction is inferred purely from the state transition sequence
 * (RINGING -> OFFHOOK = incoming answered; IDLE -> OFFHOOK directly =
 * outgoing dialed). This deliberately avoids needing
 * PROCESS_OUTGOING_CALLS / READ_CALL_LOG, both Play-Store-restricted to
 * default dialer apps — see core-recording's manifest comments.
 */
sealed class CallEvent {
    data object Idle : CallEvent()
    data object Ringing : CallEvent()
    data class Active(val direction: CallDirection) : CallEvent()
}
