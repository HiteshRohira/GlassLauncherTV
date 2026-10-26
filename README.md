# Mirage

<p align="center">
  <img src="docs/screenshots/home.png" alt="Mirage home screen" width="45%"/>
  &nbsp;
  <img src="docs/screenshots/control-center.png" alt="Mirage Control Center" width="45%"/>
</p>

**A fast, quiet home screen for Android TV with AirPlay built in.**

Mirage replaces the stock Google TV home with a calm, glass-styled launcher: a clock, an ambient background that takes its colour from the app you're on, and one row of favourite apps. Everything else stays out of the way until you ask for it. It has no ads, recommendations, accounts or telemetry.

## Screenshots

| Home | All apps |
| --- | --- |
| ![Home](docs/screenshots/home.png) | ![All apps](docs/screenshots/all-apps.png) |

| Control Center | Bluetooth |
| --- | --- |
| ![Control Center](docs/screenshots/control-center.png) | ![Bluetooth](docs/screenshots/bluetooth.png) |

| AirPlay | Phone remote |
| --- | --- |
| ![AirPlay](docs/screenshots/airplay.png) | ![Phone remote](docs/screenshots/phone-remote.png) |

Branch state: `main` carries the integrated source (`1.1.6-airplay.7` plus cleanup); `fix/review-backlog` carries a newer build (`1.1.7-airplay.8`) that has not landed on `main` yet and is the version running on the reference TV; `fix/side-panel-focus` contains an additional focus fix awaiting testing.

## Features

- **Home and All apps.** One favourites row on Home, with large 16:9 app banners. Press Down for a full-screen grid of every app, labelled and scrollable. Hold OK on an app to move, hide or inspect it.
- **Control Center.** A glass side sheet with live tiles for Wi‑Fi, Bluetooth, AirPlay, Phone remote, Inputs and Appearance, plus a shortcut to Android settings.
- **Bluetooth.** Paired devices sit one click from Home. Connect or disconnect audio devices, or pair new ones, in the same sheet.
- **Built-in AirPlay receiver.** iPhone, iPad and Mac can mirror the screen, play video (Photos, YouTube) and stream music to the TV. There's no separate app to install or keep alive. The receiver:
  - runs in a separate process from Home;
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
- Gradle `8.14.5` (installed separately; wrapper scripts are not included)

The AirPlay engine pulls UxPlay, libplist, OpenSSL and FFmpeg as git submodules:

```sh
git clone --recursive https://github.com/HiteshRohira/Mirage.git
cd Mirage/glasslauncher
gradle --no-daemon :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease
```

The first build compiles OpenSSL and FFmpeg for `armeabi-v7a` and `arm64-v8a`.
On `fix/review-backlog`, add `-PtvAbiOnly` for ARMv7 only; omit that property
for both ABIs. Use separate, fresh native build state when comparing variants.
For an existing clone, initialize dependencies with
`git submodule update --init --recursive`.

The unsigned APK is written under `glasslauncher/app/build/outputs/apk/release/`.
Sign it locally; an update must use the same key as the installed app. Keep keys
outside the repository. JVM tests and lint do not execute device tests.
The phone remote maintains a local ADB connection after Android authorization;
see [`glasslauncher/PHONE-REMOTE.md`](glasslauncher/PHONE-REMOTE.md).

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

Mirage started as a fork of [Glass Launcher](https://github.com/namillis/GlassLauncherTV) by mithun.ys / pixel, but it is no longer a GitHub fork: it has diverged into its own project. The credits stay — its home screen still owes the original a lot — and it has since grown into something quite different:
- a redesigned interface;
- an AirPlay receiver built into the launcher instead of a separate app;
- a phone remote served by the TV itself;
- a long list of performance and reliability work for low-end TV hardware.
