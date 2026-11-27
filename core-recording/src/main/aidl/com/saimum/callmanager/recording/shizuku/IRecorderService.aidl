package com.saimum.callmanager.recording.shizuku;

import com.saimum.callmanager.recording.shizuku.IAudioSink;

/**
 * Runs inside the Shizuku "user service" process (uid 2000 / shell), which is
 * allowed to capture call audio (CAPTURE_AUDIO_OUTPUT) and to grant runtime
 * permissions — things a normal app can't do.
 *
 * Every method has an explicit transaction id: Shizuku reserves 16777114 for
 * destroy(), and AIDL wants ids on all methods or none.
 */
interface IRecorderService {
    /** Reserved by Shizuku: the service process must exit. Do not remove or renumber. */
    void destroy() = 16777114;

    /**
     * Opens the first audio source in [sources] that works and starts streaming
     * PCM16 mono audio to [sink].
     * Returns the audio source that started (>= 0), or a negative number on
     * failure — see getLastError().
     */
    int start(in int[] sources, int sampleRateHz, IAudioSink sink) = 1;

    void stop() = 2;

    String getLastError() = 3;

    /**
     * Runs `pm grant` for each runtime permission (and whitelists the app from
     * battery optimization). Returns a short human-readable log.
     */
    String grantPermissions(String packageName, in String[] permissions) = 4;
}
