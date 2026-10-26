# Glass phone remote

This document describes `1.1.8-airplay.9` on `feature/apk-size-focus`.
Glass serves its remote at `http://<TV-IP>:48633/` on the home network.

1. Open Control Center → Phone remote and scan the QR code.
2. Enable TV controls and approve Android's debugging authorization on the TV.
   Local network debugging on port 5555 must already be enabled.
3. On iPhone, use Safari → Share → Add to Home Screen.

The QR link pairs automatically; manual pairing uses the code shown on the TV.
Each phone receives its own token. Pairing another phone rotates the code without
revoking existing phones. Cookies expire after one year; Forget paired phones
revokes all devices and unused install links. The remote can also be turned off.

The remote supports directions, OK/long-press, Home, Back, playback, volume and
Unicode text entry through an Android input method. Custom app editors may not
accept that text. Glass journals and restores the previous keyboard.

The bounded HTTP server and bundled web assets run alongside the integrated
AirPlay receiver. Requests are authenticated and rate-limited; commands use an
ordered queue. The bridge maintains a local ADB connection and starts a persistent
shell-user key injector when available, with fixed-command fallbacks. There is no
arbitrary shell endpoint or cloud service. Local HTTP traffic is unencrypted.

Home Screen installation uses a one-time install token, separate from the pairing
code. Android authorization and device-specific controls must be checked on each
supported TV. Build/unit results are not a substitute for device validation.
