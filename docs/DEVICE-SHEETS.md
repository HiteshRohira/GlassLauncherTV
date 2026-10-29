# Device control sheets

Added in Mirage 1.1.12.

Wi-Fi and Inputs now open second sheets in the existing Control Center, both at Home and in the global overlay. Back returns to the originating tile, with focus contained in the sheet. The overlay’s Picture tile retains its existing direct native-settings shortcut; a useful in-Mirage manual picture sheet is still unresolved. Home's Appearance page is unchanged.

## Wi-Fi

On Android 11+, network configurations use Android’s `WifiConfiguration.setSecurityParams` so WPA3 includes the required protected management frames and cipher/protocol policy. WPA3 joining falls back to native settings on older platforms.

The sheet shows connection status, link speed, saved networks and nearby networks, deduplicated by SSID and ordered by signal strength. Scan ages are shown. Actions include Wi-Fi on/off (off requires confirmation), scanning, saved-network reconnect and joining an open or WPA2/WPA3 personal network. Enterprise, WEP and other unsupported security schemes remain in advanced native Wi-Fi settings. Connection acceptance is labeled as a request, followed by one bounded status refresh; it is not presented as successful association until Android's connection state changes.

Android 10+ restricts ordinary applications' Wi-Fi configuration. The existing authenticated local ADB setup starts a fixed-operation shell-UID helper, rather than changing the launcher's target SDK or requesting broad Android permissions. The helper constructs WifiManager with the shell package's legitimate attribution. No arbitrary shell execution is exposed. Passwords travel as JSON on stdin, not command-line text, are cleared from the text field on connect/cancel/dismiss, and are not saved in Mirage preferences or logs. Android stores the selected Wi-Fi configuration in the usual way.

Every helper has a 14-second in-process termination watchdog and an 18-second direct-process TV timeout. No persistent input injector is started. Canceled queued work is discarded, and cancellation is checked again after local ADB connects before starting the helper. UI callbacks use a page generation to reject stale results.

Read-only inspection on TCL G06/API30 verified current network identity, link information, saved networks and nearby scan results through the bridge. No physical-TV radio toggles, network connections or credential changes were performed. Emulator testing exercises radio off/on and saved-network reconnect, restoring the original connection afterward. Password entry and joining are additionally covered with a fake backend, including Unicode and shell metacharacters in SSIDs/passwords.

## Inputs

The sheet uses the existing TV Input Framework enumeration, respects hidden inputs, displays port availability, and switches via the standard passthrough channel URI when a source is explicitly selected. Input enumeration runs off the UI thread. Unsupported devices show a clear empty state with a native settings fallback. Physical input switching is not verified by the emulator, which has no HDMI-in ports.

## Picture

Picture retains the existing native settings shortcut. Manual brightness integration is deferred. There is no custom brightness slider or vendor SDK probe in the production app.

## Validation

The initial sheets update passed 267 JVM tests, 18 emulator checks and an emulator Wi-Fi off/on/reconnect exercise that restored the original connection. Release lint had no errors and 21 warnings, including four deliberate hidden Wi-Fi API dependencies. Reads were checked on Android 11 and writes on the Android 12 emulator. Future firmware may require a different adapter. Physical HDMI switching and Wi-Fi write operations remain device-specific.

Updates preserve the application ID, signing certificate and saved launcher preference keys. Never uninstall or clear data to apply an update.
