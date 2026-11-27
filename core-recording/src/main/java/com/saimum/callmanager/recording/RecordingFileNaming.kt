package com.saimum.callmanager.recording

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds recording file names as DIRECTION_DATE_TIME by default, e.g.
 * "INCOMING_20260912_143210.wav" — or just DATE_TIME if the user turns off
 * "include direction" in Settings.
 *
 * The original spec asked for PHONE_NUMBER_DATE_TIME. That needs
 * READ_CALL_LOG, which Google Play restricts to default dialer/call-log
 * apps — see the manifest comments for the full reasoning. Rather than
 * inventing a placeholder number, the number segment is simply left out.
 */
object RecordingFileNaming {
    private val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun buildFileName(direction: CallDirection, includeDirection: Boolean = true, timestamp: Date = Date()): String {
        val datePart = formatter.format(timestamp)
        return if (includeDirection) "${direction.name}_$datePart.wav" else "$datePart.wav"
    }
}
