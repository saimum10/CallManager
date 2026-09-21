# Call Manager

A lightweight Material 3 Android app for managing call recording and
per-SIM call forwarding. Built with Kotlin and Jetpack Compose.
Requires Android 10 (API 29) or newer.

## Features

**Call recording**
- Record incoming and/or outgoing calls automatically

**Call forwarding**
- Manage forwarding per SIM (dual-SIM supported)

**Radio info / phone info**

## Use

1. Download an release APK from hare **(https://github.com/saimum10/CallManager/releases/tag/v0.2.9)** page and install it
   (`universal` works on every phone;).
2. **Recording tab:** turn on the master switch, allow the permissions,
   then choose Incoming and/or Outgoing.
3. **Forwarding tab:** pick a SIM, choose when to forward, enter the
   number, and switch it on.

Note: recent Android versions restrict access to call audio. On some
phones recordings can come out silent; turning on "Force speakerphone
while recording" often helps.

## Permission

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

Saimum & AI — [github.com/saimum10/CallManager](https://github.com/saimum10)
