# Glass TV

**A fast, quiet home screen for Android TV with AirPlay built in.**

Glass TV replaces the stock Google TV home with a calm, glass-styled launcher: a clock, an ambient background that takes its colour from the app you're on, and one row of favourite apps. Everything else stays out of the way until you ask for it. It has no ads, recommendations, accounts or telemetry.

## Features

- **Home and All apps.** One favourites row on Home, with large 16:9 app banners. Press Down for a full-screen grid of every app, labelled and scrollable. Hold OK on an app to move, hide or inspect it.
- **Control Center.** A glass side sheet with live tiles for Wi‑Fi, Bluetooth, AirPlay, Phone remote, Inputs and Appearance, plus a shortcut to Android settings.
- **Bluetooth.** Paired devices sit one click from Home. Connect or disconnect audio devices, or pair new ones, in the same sheet.
- **Built-in AirPlay receiver.** iPhone, iPad and Mac can mirror the screen, play video (Photos, YouTube) and stream music to the TV. There's no separate app to install or keep alive. The receiver:
  - runs in its own background process, so a media fault never takes Home down;
  - re-advertises itself when the network changes and repairs itself if it stops being visible;
  - decodes mirroring with asynchronous hardware MediaCodec straight to the screen surface;
  - uses low-latency audio;
  - trims YouTube HLS playlists to what the TV can play before playback starts.
- **Phone remote.** The TV serves its own remote web app on your home network:
  - scan the QR code in Control Center to pair, then add it to your Home Screen;
  - one-handed layout with a swipe touchpad or arrow buttons, playback, volume and keyboard text entry;
  - nothing is hosted in the cloud.
- **Gentle on old hardware.** Designed and tuned on a 2021 TCL Google TV (Android 11, 32-bit ARM, ~1.4 GB RAM). It works around that firmware's app freezer and boot-start restrictions.

## Building

Requirements:

- JDK 17
- Android SDK 36
- NDK `27.0.12077973`
- CMake `3.31.6`
- Gradle 8.14

The AirPlay engine pulls UxPlay, libplist, OpenSSL and FFmpeg as git submodules:

```sh
git clone --recursive https://github.com/HiteshRohira/GlassLauncherTV.git
cd GlassLauncherTV/glasslauncher
gradle :app:assembleRelease :app:testReleaseUnitTest
```

The first build compiles OpenSSL and FFmpeg for `armeabi-v7a` and `arm64-v8a`, which takes a while. Sign the APK with your own key, install it, then pick Glass TV as the Home app. The phone remote's controls use the TV's local network ADB (port 5555) once, after you approve Android's prompt. See [`glasslauncher/PHONE-REMOTE.md`](glasslauncher/PHONE-REMOTE.md).

## Project layout

- `glasslauncher/app` — the launcher: Home, All apps, Control Center, Bluetooth, the phone remote server and its web app.
- `glasslauncher/airplay` — the AirPlay engine and player:
  - native UxPlay core, with patches in `src/main/cpp/patches`;
  - Kotlin service and renderers;
  - the full-screen AirPlay player.

## Licensing

- Launcher code is under the **PolyForm Noncommercial License 1.0.0** ([LICENSE](./LICENSE), [NOTICE.md](./NOTICE.md)). Personal and other non-commercial use, modification and sharing are allowed; commercial use is not.
- The AirPlay module (`glasslauncher/airplay`) is adapted from [jqssun/android-airplay-server](https://github.com/jqssun/android-airplay-server) and builds on [UxPlay](https://github.com/FDH2/UxPlay). It is under the **GPL-3.0** ([glasslauncher/airplay/LICENSE](glasslauncher/airplay/LICENSE)).
- Third-party components keep their own licenses.

This project is personal and non-commercial; no APKs are published.

## Origins

Glass TV started as a fork of [Glass Launcher](https://github.com/namillis/GlassLauncherTV) by mithun.ys / pixel, and its home screen still owes that project a lot. It has since grown into something quite different:
- a redesigned interface;
- an AirPlay receiver built into the launcher instead of a separate app;
- a phone remote served by the TV itself;
- a long list of performance and reliability work for low-end TV hardware.

The result is a much smoother, much more capable launcher than where it began.
