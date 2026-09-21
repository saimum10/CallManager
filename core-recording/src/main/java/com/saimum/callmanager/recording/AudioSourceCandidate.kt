package com.saimum.callmanager.recording

import android.media.MediaRecorder

/**
 * Real call-audio capture sources, in the order we try them.
 *
 * [VOICE_CALL] is the "correct" source and tried first, but Android 10+
 * gates it behind CAPTURE_AUDIO_OUTPUT (system apps only) on most stock and
 * near-stock builds, so it fails to initialize for us there. [VOICE_COMMUNICATION]
 * is tried next but is meant for a VoIP app's own audio session, not a
 * native telephony call, so it rarely helps either. [VOICE_RECOGNITION] is
 * included last: it isn't behind the same restriction, and on a lot of
 * OEM/MediaTek-based builds (common on budget and China-market devices) it
 * is, in practice, the source that actually taps real call audio — this
 * matches what several long-running open-source call recorders default to
 * for exactly that reason.
 *
 * Deliberately excludes [MediaRecorder.AudioSource.MIC] as a silent
 * fallback: recording through the plain mic during a call only captures
 * whatever leaks acoustically (usually just your own voice, muffled) —
 * calling that "call recording" would be exactly the fake-success
 * situation we're avoiding. If none of these initializes or produces real
 * audio, the honest answer is [RecordingCapability.INIT_UNSUPPORTED] /
 * [RecordingCapability.SILENT], not a degraded mic recording presented as
 * if it were the real thing.
 */
enum class AudioSourceCandidate(val mediaRecorderConstant: Int) {
    VOICE_CALL(MediaRecorder.AudioSource.VOICE_CALL),
    VOICE_COMMUNICATION(MediaRecorder.AudioSource.VOICE_COMMUNICATION),
    VOICE_RECOGNITION(MediaRecorder.AudioSource.VOICE_RECOGNITION);

    companion object {
        val inTryOrder: List<AudioSourceCandidate> = listOf(VOICE_CALL, VOICE_COMMUNICATION, VOICE_RECOGNITION)
    }
}
