package com.saimum.callmanager.recording

/**
 * What we honestly know about this device's ability to capture call audio.
 *
 * Android gives no guarantee here, so this status is deliberately staged
 * rather than a single up-front yes/no:
 *
 * - [UNKNOWN]: not probed yet, or an audio source initialized fine but no
 *   real call has been recorded through it yet. A bench test with no call
 *   in progress can't prove real audio capture — silence during a bench
 *   probe is expected, not a failure signal. The UI shows this as
 *   "not yet verified" and still lets the user try.
 * - [INIT_UNSUPPORTED]: no candidate AudioSource could even initialize
 *   (SecurityException / IllegalStateException from every candidate). This
 *   is a fast, confident "unavailable" — shown immediately as
 *   "Recording unavailable on this device."
 * - [SUPPORTED]: a real call recording produced non-silent audio. Confirmed
 *   working.
 * - [SILENT]: a real call recording completed technically successfully but
 *   the captured audio was silent for its whole duration — the source
 *   initialized but the OS/OEM didn't actually route call audio into it.
 *   Treated the same as unsupported in the UI: never reported as a working
 *   feature just because MediaRecorder/AudioRecord didn't throw.
 */
enum class RecordingCapability {
    UNKNOWN,
    INIT_UNSUPPORTED,
    SUPPORTED,
    SILENT;

    /** True only when recording is a real, confirmed-working feature on this device. */
    val isConfirmedWorking: Boolean
        get() = this == SUPPORTED

    /** True when the UI should show "Recording unavailable on this device." */
    val isConfirmedUnavailable: Boolean
        get() = this == INIT_UNSUPPORTED || this == SILENT
}
