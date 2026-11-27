package com.saimum.callmanager.recording.shizuku;

/**
 * Implemented by the app. The Shizuku user service (running as the shell user)
 * pushes captured call audio here as little-endian PCM16 mono chunks.
 *
 * oneway: the recorder thread in the shell process never blocks on the app.
 * Calls on one binder object are delivered in order.
 */
oneway interface IAudioSink {
    void onPcm(in byte[] pcm);
}
