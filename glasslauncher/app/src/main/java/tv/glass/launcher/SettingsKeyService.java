package tv.glass.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/** Filters only Settings: no window content, gestures, or accessibility events are read. */
public class SettingsKeyService extends AccessibilityService {
    static boolean isSettingsKey(int code) {
        // TCL G06/AR16 sends TCL_QUICK_MENU (4077), rather than Android SETTINGS.
        return code == 4077 || code == KeyEvent.KEYCODE_SETTINGS;
    }
    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (!isSettingsKey(event.getKeyCode())) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            startActivity(new Intent(this, ControlCenterActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        }
        return true; // Consume both edges, including holds; leave every other key alone.
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }
}
