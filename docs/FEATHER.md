# Feather ambient interface

The `ui/feather-ambient` branch extends the `airplay-status` fork with the approved native Android interface. It uses Java Views and Canvas, not a WebView or a bundled website.

## Interface

- App-colour ambient background, bundled Outfit font, floating glass dock and landscape 16:9 app banners.
- Focus lifts and enlarges the selected app without a white border or glow.
- Three icon-only home controls: AirPlay, Bluetooth and Control Center. Feather outline vectors are bundled locally; AirPlay retains the approved outline symbol.
- Floating Control Center provides network settings, inputs, AirPlay state, up to three actual paired Bluetooth devices inline, pairing and launcher settings. The dedicated Bluetooth menu retains the complete paired-device list and existing connection backend.
- A virtualized app drawer shows two complete rows and scrolls with the D-pad. Back restores the home app that opened it; nested Bluetooth returns to its Control Center row, and closing Control Center restores its trigger.

## Upgrade behavior

The first Feather launch selects five columns, 14% card corners, no focus border, and the approved favorites order: Plezy, Apple TV, YouTube, Prime Video and JioTV+. Every other installed app remains available in All apps; no app is uninstalled or cleared.

The previous layout, favorites order/count and system-wallpaper flag are retained in private `pre_feather_*` preferences. Existing wallpaper URIs, slideshow folder/timing and image files are preserved. Ambient mode pauses wallpaper loading and slideshow work instead of deleting those settings. Toggle it under Settings → Wallpaper → App-colour ambient background. Choosing a wallpaper or enabling slideshow/system wallpaper switches out of ambient mode.

AirPlay monitoring, receiver keep-alive, remote services and Bluetooth connection logic are inherited from the earlier fork. Device availability is read from Android; the launcher does not fabricate receiver readiness or paired devices.

## Validation and builds

Release version: `1.0.6-feather.1` (version code 14). The original fork supports API 26+; this interface was checked on an API 31 ARM64 Android TV emulator. Release JVM tests: 213 passing. Android lint: 0 errors, 11 warnings. Native D-pad checks cover app navigation, grid scrolling, exact Back restoration, both Bluetooth entry points, Control Center, unavailable AirPlay and launching/returning from a native activity.

Build from `glasslauncher/` using JDK 17 and Android SDK 36:

```sh
gradle --no-daemon --max-workers=2 \
  -Dorg.gradle.jvmargs="-Xmx3g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8" \
  :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease
```

Sign locally with the existing fork's signing key before an in-place update. Keep signing files outside the repository. Never uninstall the launcher to apply an update; a matching-signature package replacement preserves its app data.

Emulator-only artwork fixture APKs, device backups, recordings, screenshots, credentials and signing material are not included in this repository. On the Android 11 TCL TV (1280×720), an in-place update preserved the launcher and receiver data directories and the Home role. Home, the app grid, Control Center, the live paired-device menu and AirPlay Ready status were visually checked. This does not constitute a new Bluetooth connection, casting, DRM playback or reboot test.

## Asset licenses

- Outfit: SIL Open Font License, bundled at `glasslauncher/app/src/main/assets/licenses/Outfit-OFL.txt`.
- Feather: MIT, bundled at `glasslauncher/app/src/main/assets/licenses/Feather-MIT.txt`.
- Installed applications supply their own banners; the launcher does not download artwork from a server.
