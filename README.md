# Call Manager

A Android app for managing call recording and per-SIM call forwarding. Requires Android 10 (API 29) or newer.

## Features

**Call recording**
- Record incoming and/or outgoing calls automatically

**Call forwarding**
- Manage forwarding per SIM (dual-SIM supported)

**Radio info / phone info**

## Use

1. Download an release APK from hare **[DOWNLOAD](https://github.com/saimum10/CallManager/releases/tag/v0.2.9)** page and install it
   (`universal` works on every phone;).
2. **Recording tab:** turn on the master switch, allow the permissions,
   then choose Incoming and/or Outgoing.
3. **Forwarding tab:** pick a SIM, choose when to forward, enter the
   number, and switch it on.

Note: recent Android versions restrict access to call audio. On some
phones recordings can come out silent; turning on "Force speakerphone
while recording" often helps.

### Recording with Shizuku (recommended)

A normal app is refused the real call-audio source on Android 10+. With
[Shizuku](https://shizuku.rikka.app/) running, Call Manager records the call
audio from a small helper process that Shizuku starts as the shell user — the
same way Shizuku-based call recorders work.

1. Install and start Shizuku (wireless debugging or root).
2. Open the **Recording** tab → **Shizuku** card → **Allow with Shizuku**.
3. Tap **Grant permissions** (or just turn on the Call Recording switch):
   Shizuku grants the microphone / phone-state / notification permissions and
   exempts the app from battery optimization — no permission dialogs.
4. Make a test call. If Shizuku can't open a call-audio source on your phone,
   the app shows the exact reason and falls back to normal in-app recording.

Shizuku has to be running again after a reboot (unless you use root).

## How call detection works

Call Manager has no always-running service. Android wakes the app through a
manifest-registered `PHONE_STATE` receiver when a call goes off-hook (the app
UI can be closed, and it keeps working after a reboot). If recording is on for
that direction, a short-lived foreground service records the call, saves it
and stops itself when the call ends. Between calls nothing runs.

Limits worth knowing:
- Android 12+ only lets an app start a foreground service from the background
  if it is exempt (for example exempted from battery optimization — Shizuku
  sets this up). Without that, Android may refuse and that call is not recorded.
- Without Shizuku, the in-app microphone recorder started from the background
  may be muted by Android (silent recordings on many phones).
- If you force-stop the app from system settings, Android delivers nothing to
  it until you open it once again.

## Permission

|---|---|
| Microphone | Record call audio (required for recording) |
| Phone state | Detect when a call starts and ends (required for recording) |
| Phone calls | Send forwarding codes to your carrier |
| Notifications | Show the recording status notification (optional) |
| Foreground service (microphone) | Runs only for the duration of a recorded call (started when the call begins, stopped when it ends) |
| Modify audio settings | Speakerphone option |
| Shizuku (optional) | Records call audio as the shell user and grants the permissions above |

## License

Apache License 2.0

## Developed by

Saimum & AI — [PROFILE](https://github.com/saimum10)
