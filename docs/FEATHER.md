# Glass TV interface

The native Android interface uses Java Views and Canvas. It is part of the
launcher, not a WebView or the removed marketing website.

- Home shows app-colour ambient light, the clock and a favourites row.
- Down opens All apps; long-press opens app actions.
- The top controls open AirPlay, Bluetooth and Control Center.
- Control Center contains live status tiles and embedded AirPlay, Phone remote
  and Appearance pages. Paired devices are listed in the Bluetooth panel.
- Wallpaper, slideshow, shortcuts and backup settings remain available.

The current installed reference build is `1.1.7-airplay.8` on
`fix/review-backlog`. It preserves the user's app order; no fixed personal
favourites are inserted by that branch. A side-panel focus fix is staged on
`fix/side-panel-focus` and awaits testing.

Outfit and Feather licence files are bundled under
`glasslauncher/app/src/main/assets/licenses/`. Installed apps provide their own
banners. See the root README for build requirements and source branches.
