package com.saimum.callmanager.recording

data class RecordingSettings(
    val includeDirectionInFileName: Boolean = true,
    val autoDeleteInterval: AutoDeleteInterval = AutoDeleteInterval.OFF,
    /** Null means no limit. */
    val maxStorageBytes: Long? = null,
    val notifyOnFinish: Boolean = true,
    /**
     * A persisted SAF tree URI (as a string) the user picked in Settings.
     * Null means "app-private storage only" — the default and always-works
     * fallback. When set, finished recordings additionally get copied here
     * as a best-effort convenience copy; the app-private copy in
     * RecordingLibraryStore remains the source of truth for playback/delete.
     */
    val customFolderUri: String? = null,
    /**
     * Forces AudioManager into MODE_IN_CALL with the speakerphone on for the
     * duration of the recording. Off by default — it's audible to everyone
     * near the call, so it's the user's call to make, not a silent default.
     *
     * Why it exists: on many devices, VOICE_CALL/VOICE_RECOGNITION sources
     * initialize fine (recording "starts") but the OS never actually routes
     * the other party's audio into that source, so the file comes out
     * silent. Forcing speaker mode makes the downlink audio physically
     * audible near the mic, which is what several long-running open-source
     * call recorders (e.g. SW Call Recorder) rely on as their real fallback
     * on hardware where the "proper" tap doesn't work.
     */
    val forceSpeakerphoneWhileRecording: Boolean = false
)
