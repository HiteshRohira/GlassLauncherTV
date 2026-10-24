# Changelog

All notable changes to Glass Launcher land here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- A TV-hosted, PIN-paired phone remote with navigation, playback, volume and
  Unicode text input. Safari can save it to the iPhone Home Screen.

### Changed

- Native Feather home uses an app-colour ambient background and outline controls.
- Control Center opens Android system settings separately from Appearance.
  Appearance slides inside the same glass dialog; paired devices and the Pair
  device button are removed from Control Center.
- Bluetooth device rows toggle connect/disconnect directly.
- The glass app menu contains Move, Hide and App info, with Move selected first.
- This approved in-dialog Appearance flow supersedes the earlier unreleased
  full-height settings sheet. Existing wallpaper, shortcuts and backup settings
  remain available through the embedded Appearance categories.

## [1.0.6] — 2026-10-19

### Added

- **Use system wallpaper.** A new Wallpaper setting lets Android draw the
  system wallpaper behind the launcher, so live wallpapers and wallpaper apps
  show on the home screen. The launcher's own wallpaper and slideshow pause
  while it is on, and picking a wallpaper or slideshow folder turns it off.
  The glass panels are tinted but not blurred in this mode.
- **Favorites background.** A new Appearance setting turns off the glass
  plate behind the favorites row, on the home screen and in the app drawer.
  It is on by default and is included in backups.

### Changed

- **Backups include custom icons.** Back up settings now saves a .zip file
  with the settings and every custom app and input icon, and restoring it
  brings the icons back. Older .txt backups still restore.

### Fixed

- **UP from the home row lands on Settings.** Pressing UP from an app on the
  home row now selects the Settings (gear) pill instead of the Wi-Fi pill.

## [1.0.5] — 2026-10-19

### Fixed

- **Bluetooth menu no longer crashes the launcher.** Closing the Bluetooth
  menu (Open Settings, Bluetooth settings, Rename or unpair, or Back) could
  crash the launcher on Android 14 when a headset-profile device was
  connected. The launcher restarted instead of opening Settings, and the
  crash could also clear Glass Launcher as the default home app.

## [1.0.4] — 2026-10-18

### Changed

- **Moving an app inserts it instead of swapping.** In the app drawer, a
  moved app is placed at its new spot and the apps in between shift over by
  one, instead of trading places with the app above or below it.
- **Moves wrap between rows.** LEFT at the start of a row moves the app to
  the end of the row above, and RIGHT at the end moves it to the start of the
  next row.
- **Favorites follow the same rules.** Moving a favorite down drops it into
  the grid under the same column. Moving an app up into favorites inserts it
  at that spot; if favorites are full, the last favorite moves down to the
  start of the grid.
- **Favorites can move into the grid from the home screen.** While moving a
  favorite on the home screen, press DOWN to open the app drawer and keep
  moving it there.
- **The app options menu wraps.** Pressing UP on the top option jumps to the
  bottom one, and DOWN on the bottom option jumps to the top.

## [1.0.3] — 2026-10-18

### Fixed

- **The Bluetooth pill hides with the rest of the toolbar again.** It stayed
  on screen in idle mode and in the app drawer, including drawer edit mode,
  while the Wi-Fi and Settings pills hid. The 2.8.1 fix for this was lost
  when the launcher was renamed.

## [1.0.2] — 2026-10-18

### Changed

- **The slideshow shuffles.** Images play in a random order instead of by
  file name. Every image shows once before the order reshuffles, and a
  restart carries on where the slideshow left off.

### Fixed

- **The slideshow folder picker finds newly copied images.** Images copied to
  the TV with `adb push` or a file manager used to stay hidden until the next
  reboot, so the picker said "No image folders found". Opening the picker now
  has Android index the TV's storage first.
- **Photo access means full access.** On Android 14 and later, the launcher
  no longer accepts "selected photos only" access, which left the folder list
  empty. It asks for full photo access instead.

## [1.0.1] — 2026-10-09

### Changed

- **The app shows its full name, Glass Launcher.** The label under the app
  tile and the TV banner now say Glass Launcher instead of Glass.

## [1.0.0] — 2026-10-09

### Changed

- **New name and app ID.** The launcher is now Glass Launcher, with the app ID
  `tv.glass.launcher`. It installs as a new app next to the old one. Set Glass
  Launcher as the Home app, then uninstall the old launcher.
- **New icon and TV banner.** A frosted-glass card over a sea-glass orb,
  matching the launcher's glass menus.
- **Settings start fresh.** Backups made with the old app use a different
  format and can't be restored. The Google TV Home setup asks for the one-time
  debugging authorization again.

Entries below are from before the rename.

## [2.8.0] — 2026-10-02

### What's new

- **Bluetooth panel.** Selecting the Bluetooth pill now opens a menu right
  under it instead of jumping to system settings. It lists your paired
  devices, with the connected ones first, and updates live as they connect
  and disconnect. **Pair new device** searches for nearby devices in the same
  menu; pick one to pair, and the TV shows its usual PIN or confirmation
  prompt. On Android 11 and older, Pair new device opens the TV's pairing
  screen, since searching from an app there needs location access.
- **Device view.** Select any paired device in the panel to open its own
  view. Headphones, speakers and soundbars get **Connect** or **Disconnect**.
  On Android TV, **Rename or unpair** opens the TV's own settings page for that
  device. Remotes, keyboards, mice and controllers reconnect on their own when
  you press a button, and the view says so.
- The bottom of the panel always has a way into system settings. Devices with
  their own Bluetooth settings screen get **Bluetooth settings**. Android TV
  doesn't have one, so on TVs it reads **Open Settings** and opens the Settings
  home page, where Remotes & accessories lives.

### Fixed

- **Bluetooth settings opened the main Settings page on Android TV.** Android
  TV's Settings app has no standard Bluetooth screen, so the shortcut fell back
  to the Settings home page. It now opens the TV's Bluetooth pairing screen.

## [2.7.0] — 2026-10-01

### What's new

- **Clearer network icon, now with Ethernet.** The network pill uses the
  standard Wi-Fi icon when you're connected and the crossed-out Wi-Fi icon when
  you're not, instead of a filled-vs-outline shape. On a wired connection it
  shows an Ethernet icon, so you can tell wired from wireless at a glance.
  Updates live as the connection changes.
- **Bluetooth status in the top bar.** On TVs and boxes with Bluetooth, a new
  pill sits next to Wi-Fi and shows whether a device (soundbar, headphones,
  speaker, remote) is connected, Bluetooth is on with nothing connected, or
  Bluetooth is off. Select it to open the system Bluetooth screen to see
  connected devices and pair new ones. On Android 12 and newer, the first
  press asks for the Nearby devices permission, which the pill needs to see
  connected devices. Remotes and controllers don't count as connected, so a TV
  that pairs its remote over Bluetooth doesn't show "connected" all the time.
  On devices without Bluetooth the pill never appears and the toolbar is
  unchanged.
- The Wi-Fi, Ethernet and Bluetooth icons are
  [Material Symbols](https://github.com/google/material-design-icons) (Apache 2.0).

## [2.6.2] — 2026-09-24

### Changed

- **Settings and app-options menus now use an edge-glass slate style.** Both
  menus share a dark slate panel with a subtle upper sheen and fading rim that
  matches the favorites bar, a unified 14dp corner radius, consistent row
  spacing, and a slightly smoother opening animation. The app-options menu now
  sizes to its content. No additional live blur is used.

## [2.6.1] — 2026-09-22

### Changed

- **Settings now open into a compact category hub.** Appearance, wallpaper and
  idle behavior, apps and shortcuts, default Home and backup, and system
  settings are grouped into predictable pages with titles, state summaries,
  drill-through chevrons, D-pad hints, and consistent control alignment.
- **The Orange focus-border option is now true orange.** Existing selections of
  the previous red-orange color migrate automatically to the new value.

### Removed

- **Removed the in-app About, QR, Ko-fi, downloader-code, and external-link
  screens.** System Settings now stays easy to reach as the final top-level
  settings row.

## [2.6.0] — 2026-09-22

### Added

- **One-time Google TV Home setup.** From Settings, Glass Launcher can connect
  to the TV's local ADB daemon, select itself as the system Home app, and
  disable both Google TV launcher packages so the remote Home button keeps
  returning to Glass Launcher after normal restarts. The same page includes a
  restore action that re-enables Google TV Home.

## [2.5.4] — 2026-09-22

### Fixed

- **Idle slideshow mode now hides the entire favorites surface.** The Liquid
  Edge wallpaper-blur layer fades with the app shelf, leaving only the
  wallpaper and clock/date visible until the next remote input.

## [2.5.3] — 2026-09-22

### Changed

- **Moving between the favorites bar and app grid now follows one continuous
  path.** The selected grid surface glides from the favorites position over a
  short 220 ms eased transition and reverses the same path when returning home.
- **Wallpaper blur now fades with the grid transition.** Older TVs fade the
  cached low-resolution blur preview; Android 12+ briefly crossfades a
  GPU-blurred wallpaper copy, then returns to the existing single-layer render.
- **Grid cards stay fixed while focus changes.** Selection eases only scale and
  shadow on the card's layout position, removing the directional translation
  that made icons appear to shake. Held navigation still snaps immediately.

## [2.5.2] — 2026-09-22

### Changed

- **Favorites now sit on a Liquid Edge glass surface.** Home uses a stronger
  wallpaper blur, translucent vertical tint, and layered reflective rim; the
  mirrored favorites row in the app grid keeps the same shape with quieter
  opacity over the already-blurred wallpaper. API 26–30 uses a tiny cached
  software-blurred wallpaper preview while Android 12+ keeps live GPU blur.

### Fixed

- **App cards dim together when their context menu opens.** Reorder mode now
  commits card opacity through compositor properties in one UI transaction,
  preventing five-column and other layouts from visibly repainting cards in
  sequence while keeping the selected card bright.

## [2.5.1] — 2026-09-21

### Added

- **Focus borders are optional and customizable.** Layout settings includes a
  default-off Focus border toggle and a named high-contrast Focus color selector,
  so users can add an unmistakable remote cursor without decoding hex values.
- **Focus borders align with every app card.** The outline follows the actual
  artwork center in both the favorites bar and lower app grid.

### Changed

- **Selected app cards expand to 1.10x.** The stronger scale cue combines with
  the existing drop shadow and optional border on both favorites and the grid.

## [2.4.9] — 2026-09-21

### Fixed

- **API 26 device tests wait for the launcher's final landscape activity.**
  ActivityScenario setup now follows the portrait-to-landscape recreation before
  keeping view or dialog references, so release checks cannot act on an activity
  Android has already replaced.

## [2.4.8] — 2026-09-21

### Fixed

- **The app drawer still opens when an older TV compositor stalls blur capture.**
  Legacy PixelCopy preparation now has a bounded fallback that continues without
  blur and discards any late callback, so optional wallpaper polish cannot block
  D-pad navigation.

## [2.4.7] — 2026-09-21

### Fixed

- **Cold-emulator release checks wait for the launcher to be ready.** Device-test
  setup now establishes home-cell focus only after the shelf can accept it, and
  smoke tests wait for non-zero content dimensions instead of treating an idle
  looper as proof that the first layout finished.

## [2.4.6] — 2026-09-21

### Fixed

- **Save, Cancel, and Reset are always reachable with a TV remote.** Rename now
  marks each action explicitly focusable in touch mode when the dialog opens,
  avoiding API 26 and ROM-specific AlertDialog defaults.

## [2.4.5] — 2026-09-21

### Fixed

- **Rename Save, Reset, Cancel, and Back restore launcher focus after the dialog
  closes.** Waiting for `OnDismiss` prevents the dialog window from reclaiming
  focus after the shelf or app grid has already received it.
- **Rename-dialog device tests no longer depend on context-menu animation.** The
  menu action keeps separate coverage while dialog behavior is tested directly.

## [2.4.4] — 2026-09-21

### Fixed

- **D-pad Up reliably returns focus from the app grid to favorites.** Glass Launcher
  restores the matching favorite immediately and reasserts it after the drawer
  fade, preventing the still-visible drawer from reclaiming focus on slower TVs.
- **Release device tests synchronize with real UI state.** Layout-settings checks
  wait for the panel, page, and persisted values, while navigation tests send the
  complete app-scoped key-event pair before checking final state.

## [2.4.3] — 2026-09-20

### Fixed

- **API 26 release validation no longer races Rename-dialog focus.** The device
  tests now wait for the dialog and actions first, then explicitly establish
  the input-focus precondition required by the keyboard tests. App behavior is
  unchanged.

## [2.4.2] — 2026-09-20

### Fixed

- **App cards now use the correct tvOS 5:3 proportions.** Favorites and the
  lower app grid render at the same 400:240 shape used by Apple TV artwork,
  across every 4–7 column layout. Existing custom artwork and layout settings
  continue to work without migration.

## [2.4.1] — 2026-09-20

### Added

- **Focused app tiles now cast a soft drop shadow.** The shadow rises with the
  existing scale animation on both the favorites bar and app grid, then clears
  completely when focus moves away.

## [2.3.3] — 2026-09-20

### Fixed

- **The on-screen keyboard closes after submitting an app or input name.**
  Pressing Done/Enter now hides the keyboard and moves focus to Save, so the
  dialog actions remain fully visible and ready for D-pad navigation.
- **The rename field has balanced spacing.** Its 24 dp horizontal margins keep
  the text box from touching the full width of the dialog on any TV density.

## [2.3.1] — 2026-09-19

### Fixed

- **Custom artwork keeps its real shape and fills the tile correctly.**
  Selected images are no longer converted into square source bitmaps.
  Transparent logos keep their alpha outline and render at nearly the full
  tile height without a white or square plate. Opaque images render
  edge-to-edge across the 5:3 tile with rounded tile corners.

## [2.3.0] — 2026-09-18

### Added

- **Custom names.** Rename any app or TV input locally from its long-press
  menu, or reset it to the name supplied by Android or the TV. Names are
  included in settings backups.
- **Custom TV-input icons.** HDMI, AV, tuner, and other input tiles now
  support Change icon and Reset icon just like installed apps.

### Fixed

- **Custom icons now fill their intended area.** Landscape and portrait
  images are center-cropped instead of being placed inside a transparent
  square, and excess transparent padding around PNG artwork is trimmed.
- **The app drawer keeps control after choosing an icon.** Refreshing one
  tile no longer rebinds the hidden favorites shelf and steals D-pad focus
  while the drawer remains visible.

## [2.2.0] — 2026-06-26

### Added

- **Custom app icons.** Long-press any app to choose a local PNG, JPEG,
  or WebP image. Glass Launcher keeps a private optimized copy, applies it
  to both launcher tiles and shortcut chips, and provides a Reset icon
  action to restore the app-provided artwork.

### What's new

- **Back up and restore your setup.** Save your layout — app order, home
  favourites, hidden apps, button shortcuts and clock style — to a file,
  and load it back later or on another TV. Find it under Settings →
  Backup & restore.
- **Wallpaper slideshow.** Under Settings → Wallpaper / Slideshow, pick a
  folder of images and have your wallpaper change on its own — every 30
  seconds up to 10 minutes, and/or a fresh one each time the TV starts.
  Picking a single wallpaper turns the slideshow off again. (Choosing a
  folder asks once for permission to read your photos.) It only runs while
  you're on the home screen, so it never slows anything down or drains
  power in the background, and your home screen still appears instantly on
  startup.
