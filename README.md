# Call Manager

A lightweight Material 3 Android app for managing call recording and
per-SIM call forwarding. Built with Kotlin and Jetpack Compose.
Requires Android 10 (API 29) or newer.

## Features

**Call recording**
- Record incoming and/or outgoing calls automatically
- Recordings library with playback and delete
- Auto-delete after 3 / 7 / 15 / 30 days, and an optional storage limit
- Optional extra copy of each recording to a folder you choose
- Optional "Force speakerphone while recording" for phones that capture silence
- Warns when a recording finished but contains no audio

**Call forwarding**
- Manage forwarding per SIM (dual-SIM supported)
- Forward always, when busy, when unanswered, or when unreachable
- Live forwarding status from the network for "forward always"

**General**
- Light, dark, or system theme
- Status screen with quick shortcuts to Recording and Forwarding

## Use

1. Download an APK from the **Releases** page and install it
   (`universal` works on every phone; release-build APKs are unsigned).
2. **Recording tab:** turn on the master switch, allow the permissions,
   then choose Incoming and/or Outgoing.
   While it is on, a small "Call recording is on" notification is shown.
   After a reboot, open the app once to start it again.
3. **Forwarding tab:** pick a SIM, choose when to forward, enter the
   number, and switch it on.
4. **Settings:** file naming, auto-delete, storage limit, backup folder,
   theme.

Note: recent Android versions restrict access to call audio. On some
phones recordings can come out silent; turning on "Force speakerphone
while recording" often helps.

## Permissions

| Permission | Why |
|---|---|
| Microphone | Record call audio (required for recording) |
| Phone state | Detect when a call starts and ends (required for recording) |
| Phone calls | Send forwarding codes to your carrier |
| Notifications | Show the recording status notification (optional) |
| Foreground service (microphone) | Keep recording while the app is in the background |
| Modify audio settings | Speakerphone option |

## License

Apache License 2.0

## Developed by

Saimum & AI — [github.com/saimum10/CallManager](https://github.com/saimum10/CallManager)
