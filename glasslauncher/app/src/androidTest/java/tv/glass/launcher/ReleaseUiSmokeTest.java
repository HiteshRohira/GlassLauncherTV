package tv.glass.launcher;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.*;

import android.app.Activity;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.Before;
import org.junit.runner.RunWith;

/** Public view semantics only: also runnable against minified release code. */
@RunWith(AndroidJUnit4.class)
public class ReleaseUiSmokeTest {
    @Before public void useDpadFocusMode(){getInstrumentation().setInTouchMode(false);}
    @Test public void visibleSheetsKeepFocus_andBackRestoresHome() {
        try (ActivityScenario<LauncherActivity> scenario = ActivityScenario.launch(LauncherActivity.class)) {
            await(scenario, a -> a.hasWindowFocus() && a.getWindow().getDecorView().getWidth()
                    > a.getWindow().getDecorView().getHeight());
            for (String page : new String[]{"airplay", "remote", "appearance"}) {
                scenario.onActivity(a -> {
                    View root = a.getWindow().getDecorView();
                    View anchor = described(root, false);
                    assertNotNull("visible Control Center toolbar action", anchor);
                    assertTrue(anchor.requestFocus());
                    assertTrue(anchor.performClick());
                });
                await(scenario, a -> described(a.getWindow().getDecorView(), true) != null);
                scenario.onActivity(a -> {
                    View panel = described(a.getWindow().getDecorView(), true);
                    View tile = panel.findViewWithTag(page);
                    assertNotNull(page, tile);
                    assertTrue(tile.requestFocus());
                    press(a, KeyEvent.KEYCODE_DPAD_CENTER);
                });
                await(scenario, a -> {
                    View panel = described(a.getWindow().getDecorView(), true);
                    View back = panel == null ? null : panel.findViewWithTag("detail-back");
                    return back != null && back.isShown() && back.getParent() instanceof View
                            && Math.abs(((View) back.getParent()).getTranslationX()) < .5f
                            && within(a.getCurrentFocus(), panel);
                });
                scenario.onActivity(a -> {
                    View panel = described(a.getWindow().getDecorView(), true);
                    for (int key : new int[]{KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP,
                            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT}) {
                        for (int i = 0; i < 24; i++) {
                            press(a, key);
                            assertTrue(page + " focus stays inside visible sheet", within(a.getCurrentFocus(), panel));
                        }
                    }
                    press(a, KeyEvent.KEYCODE_BACK);
                });
                await(scenario, a -> {
                    View panel = described(a.getWindow().getDecorView(), true);
                    return panel != null && a.getCurrentFocus() != null && page.equals(a.getCurrentFocus().getTag());
                });
                scenario.onActivity(a -> press(a, KeyEvent.KEYCODE_BACK));
                await(scenario, a -> described(a.getWindow().getDecorView(), true) == null
                        && a.getCurrentFocus() != null && "Control Center".contentEquals(a.getCurrentFocus().getContentDescription()));
            }
        }
    }

    private static View described(View view, boolean fullWidth) {
        if (!view.isShown()) return null;
        CharSequence description = view.getContentDescription();
        if ("Control Center".contentEquals(description == null ? "" : description)
                && (view.getWidth() >= view.getRootView().getWidth() * .8f) == fullWidth) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View match = described(((ViewGroup) view).getChildAt(i), fullWidth);
            if (match != null) return match;
        }
        return null;
    }

    private static boolean within(View view, View scope) {
        while (view != null) {
            if (view == scope) return true;
            ViewParent parent = view.getParent();
            view = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private static void press(Activity activity, int key) {
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
    }

    private interface Probe { boolean ready(Activity activity); }
    private static void await(ActivityScenario<LauncherActivity> scenario, Probe probe) {
        long end = SystemClock.uptimeMillis() + 8000;
        do {
            getInstrumentation().waitForIdleSync();
            boolean[] ready = {false};
            scenario.onActivity(a -> ready[0] = probe.ready(a));
            if (ready[0]) return;
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < end);
        fail("Timed out waiting for visible release UI state");
    }
}
