# Remote Settings button

Mirage 1.1.11 adds an optional Android accessibility service named **Mirage Settings button**. Enable it in Android's Accessibility settings to open Control Center over the current app. Settings again, or Back at the top level, dismisses the controls. This is a separate translucent task, excluded from Recents; the launcher task and the app below are preserved. Apps may pause playback when their activity loses foreground focus.

The service filters Android `KEYCODE_SETTINGS` (176) and TCL's `TCL_QUICK_MENU` (4077). Other remote keys, including Home, volume, media and navigation, pass through. Both edges and held repeats of Settings are consumed, with one toggle per press. The service cannot retrieve window content or perform gestures; announcement callbacks are ignored. It shares the existing receiver process so TCL's idle launcher freezing does not strand the handler in the Home process.

The overlay uses the existing Control Center, phone remote and Bluetooth panel. Its Picture tile opens `android.settings.DISPLAY_SETTINGS` (TCL PictureActivity on G06), exposing the firmware's real brightness/picture controls. Home's Appearance tile remains unchanged. System actions launch their native settings screens; unavailable actions leave the panel open with a message. Bluetooth Back returns to Control Center, then Back returns to the previous app.

## TCL G06 investigation (2026-10-29)

The inspected TV identifies as G06 / G06_2K_GB, MT9221_Amati / mt5867, Android 11 (SDK 30), AR2101/AR16, firmware V8-T221T02-LF1V052. Firmware reports a 1920×1080 panel; it does not identify the retail model reliably.

A physical remote press produced key 4077, then SystemUI logged that it could not resolve `android.app.action.TOGGLE_NOTIFICATION_HANDLER_PANEL`. The firmware's notification handler package is `com.google.android.apps.tv.launcherx`, whose DashboardHandler is disabled with stock Home. Registering an ordinary intent filter in Mirage cannot override that firmware package restriction. The optional key filter handles the event before that dead route without re-enabling Google Home or modifying the firmware.

## Validation

Validation for the final candidate: 267 JVM tests; 13 distinct emulator checks (the nine existing panel checks plus four Settings-key/overlay checks); release lint with 0 errors and 17 existing warnings. Both signed ABI APKs have identical shared dex/resources and retain the byte-identical 1.1.10 native receiver.

Instrumentation covers selective key filtering/held edges, overlay focus containment and dismissal, Bluetooth return navigation, and enabled-service metadata plus the Settings callback restoring the existing task after Back. Existing SidePanelFocusTest covers the launcher Control Center and detail views. Injected Android shell key events bypass the hardware input filter, so the emulator callback check does not replace checking the real TCL key on the TV. Install as a normal signed update with the same package/certificate; never uninstall Mirage or clear its data.
