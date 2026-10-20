<div align="center">

<img src="glasslauncher/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="104" alt="Glass Launcher icon" />

# Glass Launcher

**A small, local-first home screen for Android TV, Google TV, and Fire TV.**

[![Latest release](https://img.shields.io/gitea/v/release/namillis/GlassLauncherTV?gitea_url=https%3A%2F%2Fgit.namillis.me&style=for-the-badge&color=22c55e&label=latest)](https://git.namillis.me/namillis/GlassLauncherTV/releases/latest)
[![Download APK](https://img.shields.io/badge/Download-APK-09090b?style=for-the-badge&logo=android&logoColor=22c55e)](https://git.namillis.me/namillis/GlassLauncherTV/releases/download/latest/GlassLauncher.apk)
[![Downloader code 5226436](https://img.shields.io/badge/Downloader-5226436-FF6B2C?style=for-the-badge&logo=amazonfiretv&logoColor=white)](http://aftv.news/5226436)
[![Website](https://img.shields.io/badge/Website-glass.namillis.me-6ee7da?style=for-the-badge)](https://glass.namillis.me)
[![License](https://img.shields.io/badge/License-PolyForm%20NC%201.0-8b5cf6?style=for-the-badge)](./LICENSE)

</div>

## Why Glass Launcher

Stock TV home screens mix app launching with content feeds and storefronts. Glass Launcher focuses on one job: press Home, choose an app, and get out of the way.

The home screen shows your wallpaper, clock, and one row of favorite apps. Press Down for the complete app grid. Settings and customization stay out of sight until you need them. There are no ads, recommendations, accounts, or telemetry.

## Screenshots

<sub><i>Click an image to open it at full size.</i></sub>

<div align="center">
<table>
  <tr>
    <td align="center">
      <a href="docs/screenshots/home.png">
        <img src="docs/screenshots/home.png" alt="Glass Launcher home screen with the favorites shelf" width="460" loading="lazy" />
      </a><br/><sub><b>Home</b></sub>
    </td>
    <td align="center">
      <a href="docs/screenshots/app-grid.png">
        <img src="docs/screenshots/app-grid.png" alt="Glass Launcher app grid" width="460" loading="lazy" />
      </a><br/><sub><b>App grid</b></sub>
    </td>
  </tr>
  <tr>
    <td align="center">
      <a href="docs/screenshots/app-options.png">
        <img src="docs/screenshots/app-options.png" alt="Glass Launcher app options menu for reordering and managing an app" width="460" loading="lazy" />
      </a><br/><sub><b>Reorder &amp; manage</b></sub>
    </td>
    <td align="center">
      <a href="docs/screenshots/settings-menu.png">
        <img src="docs/screenshots/settings-menu.png" alt="Glass Launcher settings menu" width="460" loading="lazy" />
      </a><br/><sub><b>Settings</b></sub>
    </td>
  </tr>
</table>
</div>

## What it does

- **Favorites and app grid:** Keep the apps you use most on the home screen and open the full grid with one press. Reorder or hide entries with the remote.
- **App customization:** Long-press an app to rename it, choose custom artwork, move it, hide it, open App info, or uninstall it.
- **Appearance controls:** Choose 4 to 7 icons per row, icon roundness, focus border and color, and whether the clock shows the date, time only, or nothing.
- **Wallpapers and slideshows:** Use a single wallpaper, rotate through an image folder, or show the system wallpaper, including live wallpapers set by another app. Configure idle mode to hide the launcher UI and leave the wallpaper and clock visible.
- **Remote and TV controls:** Add supported HDMI and other TV inputs to the grid. Assign the remote's color, menu, and subtitle buttons to apps.
- **Backup and restore:** Export the launcher layout and settings to a file, then restore them after reinstalling or moving to another device.
- **Persistent Google TV Home:** An optional one-time local ADB setup makes Glass Launcher the Home app and includes a restore action for Google TV Home.

## Privacy and size

Normal launcher use makes no network requests. Glass Launcher has no analytics, advertising, account system, or background update service. Custom names, artwork, shortcuts, and settings stay on the device.

The APK includes the `INTERNET` permission only for the explicit Google TV Home setup. That flow connects to the TV's own ADB daemon at `127.0.0.1:5555` and runs a fixed set of setup or restore commands. Photo access is requested only when you choose a slideshow folder.

The UI uses the Android SDK directly, with programmatic layouts and custom recycling views. It has no runtime AndroidX dependency or native code. R8 minifies each release. The only direct production dependency is `dadb`, which is loaded for the local ADB setup and restore flow.

## Compatibility

Glass Launcher requires Android 8.0 (API 26) or newer. It is intended for:

- Android TV and Google TV devices
- Fire TV Stick and Fire TV Cube
- Mi Box, Mi Stick, and other Android TV boxes

TV input tiles depend on the device exposing inputs through Android's TV Input Framework. The built-in persistent Home setup is for Google TV devices with local ADB available on port 5555. Other devices use their system launcher-selection flow.

## Install

Glass Launcher is sideload-only. It is not published on the Google Play Store or Amazon Appstore.

### Downloader

Install the **Downloader** app by AFTVnews and enter code **5226436**. The code points to the latest Glass Launcher release.

### Direct APK

1. Download [`GlassLauncher.apk`](https://git.namillis.me/namillis/GlassLauncherTV/releases/download/latest/GlassLauncher.apk) from the latest release.
2. Move it to the TV with a USB drive, network share, or file-transfer app.
3. Allow installation from unknown sources when Android asks.
4. Open the APK and install it.

## Use Glass Launcher as Home on Google TV

After Network debugging is enabled, Glass Launcher configures the TV without a second device:

1. Enable **Developer options**, then enable **Network debugging**.
2. Open **Settings → Default home & backup → Default home** in Glass Launcher.
3. Select **Use Glass Launcher as Home**.
4. Accept Android's one-time debugging authorization prompt.

The setup selects Glass Launcher as the system Home app and disables the Google TV launcher packages. The change survives normal restarts.

Disabling Google TV Home also removes its quick-settings sidebar. Before uninstalling Glass Launcher, use **Restore Google TV Home** from the same screen. If Glass Launcher has already been removed, reconnect with ADB and run:

```shell
adb shell pm enable --user 0 com.google.android.apps.tv.launcherx
adb shell pm enable --user 0 com.google.android.tungsten.setupwraith
```

## License

Glass Launcher is available under the **PolyForm Noncommercial License 1.0.0** for personal and non-commercial use. See [LICENSE](./LICENSE) and [NOTICE.md](./NOTICE.md).
