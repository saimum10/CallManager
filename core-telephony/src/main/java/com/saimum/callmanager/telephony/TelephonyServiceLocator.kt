package com.saimum.callmanager.telephony

/**
 * Manual dependency-injection point, mirroring core-recording's
 * RecordingServiceLocator. The app module wires the real (DataStore-backed)
 * implementation in at startup.
 */
object TelephonyServiceLocator {
    @Volatile
    var forwardingPreferencesStore: ForwardingPreferencesStore? = null
}
