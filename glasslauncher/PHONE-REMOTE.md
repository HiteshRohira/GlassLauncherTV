# Glass phone remote

Glass serves its own remote at `http://<TV-IP>:48633/`. The TV and phone must be on the same home network; the Mac and an internet hosting service are not needed.

1. Open Control Center → Phone remote on the TV.
2. Scan the QR code in Safari and enter the six-digit code shown on the TV.
3. Choose Enable TV controls and accept Android's debugging authorization on the TV, selecting Always allow. Local network debugging on port 5555 must already be enabled.
4. In Safari, use Share → Add to Home Screen with Open as Web App enabled.

The remote supports directions, OK, Home, Back, playback and volume. Select a text field in the TV app, open Keyboard on the phone, type, and choose Send text. Text is inserted at the TV cursor; Delete and Enter are separate controls. Apps with custom text editors may not support Android keyboard insertion.

## Implementation

- The existing foreground monitor process hosts a small bounded HTTP server; it also runs when the optional AirPlay receiver is absent. Assets ship inside the APK. There is no cloud service, telemetry, service worker, or offline cache.
- Each phone gets its own HttpOnly, SameSite cookie token when it enters the TV's current six-digit code, and the TV then shows a new code for the next phone. A paired phone stays connected (one-year cookie) and never needs a code again. Requests require the TV's host/origin; commands are fixed and authenticated. There is no arbitrary shell endpoint. HTTP traffic stays unencrypted on the home network. Phones paired before 1.1.7 keep working: the old shared token is kept as one device.
- iOS gives a Home Screen web app its own cookie jar. After pairing, the page reloads with a one-time install token (valid 7 days) that the TV also puts in the manifest's start URL; the Home Screen app redeems it for its own token on first launch. The pairing code is never placed in a URL or manifest.
- Only `POST /api/pair` checks codes, under one brute-force budget (`PairGuard`): five distinct wrong codes per address per minute, and ten distinct wrong codes from anywhere in ten minutes pause pairing for 15 minutes with a toast on the TV. Repeating the same retired code counts once.
- Control Center → Phone remote shows the QR and code (refreshed when a phone pairs) and how many phones are paired, an on/off switch (the TV stops listening on port 48633 when off; on by default) and **Forget paired phones**, which revokes every phone and install link and shows a new code.
- Controls use an app-owned local ADB identity through the existing Dadb integration. Android TV Remote Service rejects connections originating on its own device, so that protocol cannot provide this embedded bridge.
- Keys take the fastest available path. After connecting, Glass starts `KeyInjector` once over its local ADB session, as the shell user (`CLASSPATH=<Glass APK> app_process /system/bin tv.glass.launcher.KeyInjector`). It reads `k CODE` / `l CODE` lines on stdin, injects key events through InputManager (what `input keyevent` does, without a new Java runtime per press) and exits when its stdin closes. It opens no socket and accepts only the remote's fixed key codes. If it is unavailable, volume, mute and media keys are handled inside Glass through `AudioManager` (they also work before TV controls are enabled), and everything else falls back to `input keyevent`. The sendevent fast path was removed: this TV's SELinux policy denied it, and the injector replaces it everywhere.
- `/api/key` replies `202 Accepted` at once; keys and text run on one ordered queue (24 deep, `503` when full), so request threads stay free and presses from two phones cannot be reordered. Holding OK on the phone (centre in Buttons mode, a resting finger in Touchpad mode) sends a real long press.
- Every request must arrive within 10 s in total and each address may hold six connections; the page aborts any request after 8 s (text 20 s) and never overlaps status polls.
- Before Glass selects its keyboard for a text send it records the user's keyboard in a private journal. If the receiver process dies mid-send, the next connection restores that keyboard, but only if Glass's keyboard is still selected.
- Unicode text travels through an Android input method in the same process, never through shell interpolation. Glass briefly selects its input method and restores the previous default after delivery. Its input method remains enabled for subsequent sends.

## Preview verification, 2026-10-24

Version `1.0.6-feather.2` / code 15 was installed on the real TV after the user reviewed and approved the emulator preview. The TV-hosted page, pairing, connection and Home control were verified live. Real-TV keyboard insertion and physical Bluetooth toggles were not completed because the user stopped the live test.

- Android TV emulator: Control Center cleanup, Android system settings, Appearance sliding inside the same dialog with Back returning, Phone remote QR/PIN, and glass app menu ordered Move / Hide / App info with Move selected.
- iPhone simulator: Safari pairing, standalone Home Screen launch with the paired session retained, remote Home/navigation, and actual text delivery into a native Android editor. Unicode delivery and restoration of the previous default keyboard were also checked.
- Existing release unit tests: 213 passed. Lint: zero errors, 11 existing warnings. Eight HTTP authentication/origin/input-boundary checks passed.
- Physical Bluetooth connect/disconnect and TCL background behavior remain hardware checks. The emulator has no paired Bluetooth devices and no AirPlay receiver.
- This emulator logs an OS permission error writing `/data/misc/adb/adb_keys`; accepting a live authorization works, but persistence across process/device restarts is not established by this preview. The physical TV subsequently reached Connected during installation; authorization persistence across a TV restart has not been tested.

Private recordings, screenshots, APK and emulator-only keyboard fixture are kept outside the source repository under the workspace's `private-tv-backup/glass-remote-preview-20261024/`. They must not be committed.
