package tv.glass.launcher;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.Test;
import org.junit.Before;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Exercises keys through the real activity dispatcher, rather than calling
 * the panel's navigator. Uses virtual keyboard taps for phone-remote parity
 * and D-pad-source holds/repeats for a physical remote.
 * No Bluetooth pairing, receiver toggles, remote pairing, or saved settings
 * changes are needed. The native-widget fixture has no persistence callbacks:
 * today's embedded Appearance uses a custom row navigator, while the panel's
 * Appearance contract also delegates arrows/confirm to Android widgets.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class SidePanelFocusTest {
    @Before public void useDpadFocusMode(){getInstrumentation().setInTouchMode(false);}
    private static final long TIMEOUT_MS = 10_000;
    private static final int[] DIRECTIONS = {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT
    };
    private static final int[] DEVICE_KEYS = {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP, KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS
    };

    @Test
    public void controlCenter_boundariesAndHeldDirections_keepFocusInSheet_andBackRestoresAnchor() {
        try (ActivityScenario<LauncherActivity> scenario = launch()) {
            View anchor = openControls(scenario);
            scenario.onActivity(a -> {
                ControlCenterPanel panel = controls(a);
                for (int direction : DIRECTIONS) {
                    held(a, direction, 24, panel);
                    View edge = a.getCurrentFocus();
                    press(a, direction);
                    assertSame("navigation stops at the sheet boundary", edge, a.getCurrentFocus());
                }
                // An unmapped phone/keyboard key must not reach the toolbar.
                press(a, KeyEvent.KEYCODE_MENU);
                assertFocusWithin(a, panel);
                press(a, KeyEvent.KEYCODE_BACK);
                assertFalse(panel.isVisible());
                assertSame("Back restores the opening toolbar control", anchor, a.getCurrentFocus());
            });
        }
    }

    @Test
    public void airPlay_detailContainsBoundaryRepeats_andRestoresItsOwnFocus() {
        try (ActivityScenario<LauncherActivity> scenario = launch()) {
            openControls(scenario);
            scenario.onActivity(a -> {
                press(a, KeyEvent.KEYCODE_DPAD_DOWN);
                assertEquals("airplay", a.getCurrentFocus().getTag());
                press(a, KeyEvent.KEYCODE_DPAD_CENTER);
            });
            awaitDetail(scenario, "airplay");
            exerciseDetailBoundaries(scenario);
            scenario.onActivity(a -> {
                ControlCenterPanel panel = controls(a);
                View detail = (View) field(panel, "detail");
                View anchor = (View) field(a, "mapperBtnView");
                assertTrue(anchor.requestFocus());
                panel.restoreFocus();
                assertFocusWithin(a, detail);
                press(a, KeyEvent.KEYCODE_BACK);
            });
            awaitControls(scenario, "airplay");
        }
    }

    @Test
    public void phoneRemote_detailContainsKeyboardDirections_andEnterBackReturnsToOpeningTile() {
        try (ActivityScenario<LauncherActivity> scenario = launch()) {
            openControls(scenario);
            scenario.onActivity(a -> {
                press(a, KeyEvent.KEYCODE_DPAD_DOWN);
                press(a, KeyEvent.KEYCODE_DPAD_RIGHT);
                assertEquals("remote", a.getCurrentFocus().getTag());
                press(a, KeyEvent.KEYCODE_ENTER);
            });
            awaitDetail(scenario, "remote");
            exerciseDetailBoundaries(scenario);
            scenario.onActivity(a -> {
                View back = ((View) field(controls(a), "detail")).findViewWithTag("detail-back");
                assertTrue(back.requestFocus());
                press(a, KeyEvent.KEYCODE_ENTER);
            });
            awaitControls(scenario, "remote");
        }
    }

    @Test
    public void appearance_nativeSeekBarReceivesBothArrowEdges_andButtonClicksOnKeyUp() {
        try (ActivityScenario<LauncherActivity> scenario = launch()) {
            openControls(scenario);
            AtomicReference<SeekBar> sliderRef = new AtomicReference<>();
            AtomicReference<Button> buttonRef = new AtomicReference<>();
            int[] clicks = {0};
            scenario.onActivity(a -> {
                LinearLayout content = new LinearLayout(a);
                content.setOrientation(LinearLayout.VERTICAL);
                SeekBar slider = new SeekBar(a);
                slider.setMax(100);
                slider.setProgress(50);
                slider.setKeyProgressIncrement(5);
                slider.setFocusableInTouchMode(true);
                content.addView(slider, new LinearLayout.LayoutParams(-1, 80));
                Button button = new Button(a);
                button.setText("Test action");
                button.setFocusableInTouchMode(true);
                button.setOnClickListener(v -> clicks[0]++);
                content.addView(button, new LinearLayout.LayoutParams(-1, 80));
                sliderRef.set(slider);
                buttonRef.set(button);
                // Inject only content; retain the real panel, host, view tree,
                // animation, and LauncherActivity.dispatchKeyEvent path.
                invoke(controls(a), "openDetail",
                        new Class<?>[]{String.class, String.class, View.class},
                        "appearance", "Appearance", content);
            });
            awaitDetail(scenario, "appearance");
            scenario.onActivity(a -> {
                SeekBar slider = sliderRef.get();
                Button button = buttonRef.get();
                View detail = (View) field(controls(a), "detail");
                assertTrue(slider.requestFocus());
                press(a, KeyEvent.KEYCODE_DPAD_RIGHT);
                assertEquals("native SeekBar handles Right", 55, slider.getProgress());
                press(a, KeyEvent.KEYCODE_DPAD_LEFT);
                assertEquals("native SeekBar handles Left", 50, slider.getProgress());
                held(a, KeyEvent.KEYCODE_DPAD_RIGHT, 30, detail);
                assertEquals(100, slider.getProgress());
                assertSame("Right at max stays on the slider", slider, a.getCurrentFocus());
                held(a, KeyEvent.KEYCODE_DPAD_LEFT, 30, detail);
                assertEquals(0, slider.getProgress());
                assertSame("Left at min stays on the slider", slider, a.getCurrentFocus());
                press(a, KeyEvent.KEYCODE_DPAD_DOWN);
                assertSame("Down still reaches the next widget", button, a.getCurrentFocus());
                long downTime = SystemClock.uptimeMillis();
                dispatch(a, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0);
                assertEquals("Button activation waits for release", 0, clicks[0]);
                dispatch(a, downTime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0);
                assertEquals("matching UP reaches the native button", 1, clicks[0]);
                assertFocusWithin(a, detail);
                press(a, KeyEvent.KEYCODE_BACK);
            });
            awaitControls(scenario, "appearance");
        }
    }

    @Test
    public void embeddedAppearance_navigationStaysInDetail_andBackReturnsBeforeClosing() {
        try (ActivityScenario<LauncherActivity> scenario = launch()) {
            openControls(scenario);
            scenario.onActivity(a -> {
                press(a, KeyEvent.KEYCODE_DPAD_DOWN);
                press(a, KeyEvent.KEYCODE_DPAD_DOWN);
                press(a, KeyEvent.KEYCODE_DPAD_RIGHT);
                assertEquals("appearance", a.getCurrentFocus().getTag());
                press(a, KeyEvent.KEYCODE_BUTTON_A);
            });
            awaitDetail(scenario, "appearance");
            scenario.onActivity(a -> {
                View detail = (View) field(controls(a), "detail");
                assertDeviceEdgesDelivered(a);
                held(a, KeyEvent.KEYCODE_DPAD_DOWN, 24, detail);
                held(a, KeyEvent.KEYCODE_DPAD_UP, 24, detail);
                // The framework Back route must respect the same page stack
                // as a physical/phone KEYCODE_BACK, without discarding it.
                a.onBackPressed();
            });
            awaitControls(scenario, "appearance");
            scenario.onActivity(a -> {
                press(a, KeyEvent.KEYCODE_BACK);
                assertFalse(controls(a).isVisible());
            });
        }
    }

    @Test
    public void backDuringBothSlides_isHonoredAfterAnimation_forKeysAndFramework() {
        for (boolean framework : new boolean[]{false, true}) {
            try (ActivityScenario<LauncherActivity> scenario = launch()) {
                View anchor = openControls(scenario);
                scenario.onActivity(a -> {
                    controls(a).openAppearance();
                    assertTrue(controls(a).isTransitioning());
                    if (framework) a.onBackPressed(); else press(a, KeyEvent.KEYCODE_BACK);
                    assertTrue("navigator stays attached until entrance completes",
                            (Boolean) field(a, "embeddedSettings"));
                });
                awaitControls(scenario, "appearance");
                scenario.onActivity(a -> {
                    assertFalse((Boolean) field(a, "embeddedSettings"));
                    controls(a).openAirPlay();
                });
                awaitDetail(scenario, "airplay");
                scenario.onActivity(a -> {
                    if (framework) a.onBackPressed(); else press(a, KeyEvent.KEYCODE_BACK);
                    assertTrue(controls(a).isTransitioning());
                    if (framework) a.onBackPressed(); else press(a, KeyEvent.KEYCODE_BACK);
                });
                await(scenario, "queued second Back closes the sheet",
                        a -> !controls(a).isVisible() ? anchor : null);
                scenario.onActivity(a -> assertSame("Back restores anchor (framework="+framework
                        +", touchMode="+anchor.isInTouchMode()+", anchorShown="+anchor.isShown()+")",
                        anchor, a.getCurrentFocus()));
            }
        }
    }

    @Test
    public void cancellingSlide_discardsPendingBack_beforeSheetReopens() {
        try (ActivityScenario<LauncherActivity> scenario = launch()) {
            View anchor = openControls(scenario);
            scenario.onActivity(a -> {
                ControlCenterPanel panel = controls(a);
                panel.openAirPlay();
                press(a, KeyEvent.KEYCODE_BACK);
                panel.hide();
                panel.show(anchor, "network", false);
            });
            SystemClock.sleep(650);
            scenario.onActivity(a -> {
                assertTrue("old transition must not close the reopened sheet", controls(a).isVisible());
                assertFocusWithin(a, controls(a));
            });
        }
    }

    @Test
    public void bluetoothHandoff_keepsFocusInVisiblePanel_onNavigationAndReturn() {
        // Standard runtime-permission grant for this emulator-only test. No
        // scan or pairing action is activated; the sheet can also be empty.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            for (String permission : new String[]{android.Manifest.permission.BLUETOOTH_CONNECT,
                    android.Manifest.permission.BLUETOOTH_SCAN}) {
                android.content.Context target = getInstrumentation().getTargetContext();
                if(target.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                    getInstrumentation().getUiAutomation().grantRuntimePermission(
                            target.getPackageName(), permission);
                }
            }
        }
        try (ActivityScenario<LauncherActivity> scenario = launch()) {
            View anchor = openControls(scenario);
            scenario.onActivity(a -> {
                press(a, KeyEvent.KEYCODE_DPAD_RIGHT);
                assertEquals("bluetooth", a.getCurrentFocus().getTag());
                press(a, KeyEvent.KEYCODE_ENTER);
            });
            await(scenario, "visible Bluetooth sheet", a -> {
                BluetoothPanel bt = (BluetoothPanel) field(a, "btPanel");
                return bt != null && bt.isVisible() ? bt : null;
            });
            scenario.onActivity(a -> {
                BluetoothPanel bt = (BluetoothPanel) field(a, "btPanel");
                View overlay = (View) field(bt, "overlay");
                assertFalse(controls(a).isVisible());
                assertFocusWithin(a, overlay);
                assertDeviceEdgesDelivered(a);
                for (int direction : DIRECTIONS) held(a, direction, 24, overlay);
                press(a, KeyEvent.KEYCODE_BACK);
                assertFalse(bt.isVisible());
                assertTrue(controls(a).isVisible());
                assertFocusWithin(a, controls(a));
                assertEquals("bluetooth", a.getCurrentFocus().getTag());
            });
            awaitControls(scenario, "bluetooth");
            scenario.onActivity(a -> {
                press(a, KeyEvent.KEYCODE_ESCAPE);
                assertFalse(controls(a).isVisible());
                assertSame(anchor, a.getCurrentFocus());
            });
        }
    }

    @Test
    public void deviceKeys_reachAndroidViewDispatch_onBothEdges_includingDuringPageAnimation() {
        try (ActivityScenario<LauncherActivity> scenario = launch()) {
            openControls(scenario);
            scenario.onActivity(a -> {
                assertDeviceEdgesDelivered(a);
                press(a, KeyEvent.KEYCODE_DPAD_DOWN);
                press(a, KeyEvent.KEYCODE_DPAD_CENTER);
                assertTrue("exercise an active slide", (Boolean) field(controls(a), "sliding"));
                assertDeviceEdgesDelivered(a);
            });
            awaitDetail(scenario, "airplay");
            scenario.onActivity(SidePanelFocusTest::assertDeviceEdgesDelivered);
        }
    }

    private static void assertDeviceEdgesDelivered(LauncherActivity a) {
        View focused = a.getCurrentFocus();
        assertNotNull(focused);
        List<String> seen = new ArrayList<>();
        // Observe the platform view-dispatch path and consume there, before
        // AudioService/PowerManager can change emulator volume or sleep state.
        focused.setOnKeyListener((v, key, event) -> {
            seen.add(key + ":" + event.getAction());
            return true;
        });
        try {
            for (int key : DEVICE_KEYS) {
                seen.clear();
                press(a, key);
                assertEquals("device key " + key + " delivers both edges", 2, seen.size());
                assertEquals(key + ":" + KeyEvent.ACTION_DOWN, seen.get(0));
                assertEquals(key + ":" + KeyEvent.ACTION_UP, seen.get(1));
            }
        } finally {
            focused.setOnKeyListener(null);
        }
    }

    private static void exerciseDetailBoundaries(ActivityScenario<LauncherActivity> scenario) {
        scenario.onActivity(a -> {
            View detail = (View) field(controls(a), "detail");
            assertFocusWithin(a, detail);
            held(a, KeyEvent.KEYCODE_DPAD_UP, 24, detail);
            View top = a.getCurrentFocus();
            assertEquals("top boundary reaches the detail Back row", "detail-back", top.getTag());
            press(a, KeyEvent.KEYCODE_DPAD_UP);
            assertSame(top, a.getCurrentFocus());
        });
        // Drain layout/scroll requests between navigation passes, just as
        // independent remote presses do; each pass also tests real repeats.
        getInstrumentation().waitForIdleSync();
        scenario.onActivity(a -> {
            View detail = (View) field(controls(a), "detail");
            held(a, KeyEvent.KEYCODE_DPAD_DOWN, 24, detail);
            View bottom = a.getCurrentFocus();
            assertFalse("Down reaches an action below Back", "detail-back".equals(bottom.getTag()));
            press(a, KeyEvent.KEYCODE_DPAD_DOWN);
            assertSame("Down at final action is contained", bottom, a.getCurrentFocus());
            press(a, KeyEvent.KEYCODE_DPAD_LEFT);
            assertSame(bottom, a.getCurrentFocus());
            press(a, KeyEvent.KEYCODE_DPAD_RIGHT);
            assertSame(bottom, a.getCurrentFocus());
            assertFocusWithin(a, detail);
        });
    }

    private static View openControls(ActivityScenario<LauncherActivity> scenario) {
        AtomicReference<View> anchor = new AtomicReference<>();
        scenario.onActivity(a -> {
            View button = (View) field(a, "mapperBtnView");
            assertTrue(button.requestFocus());
            assertTrue(button.performClick());
            anchor.set(button);
        });
        awaitControls(scenario, "network");
        return anchor.get();
    }

    private static void awaitControls(ActivityScenario<LauncherActivity> scenario, String tag) {
        await(scenario, "Control Center focused on " + tag, a -> {
            ControlCenterPanel panel = controls(a);
            View focus = a.getCurrentFocus();
            return panel != null && panel.isVisible() && field(panel, "detail") == null
                    && focus != null && tag.equals(focus.getTag()) && within(focus, panel)
                    ? panel : null;
        });
    }

    private static void awaitDetail(ActivityScenario<LauncherActivity> scenario, String key) {
        await(scenario, "settled focused " + key + " detail", a -> {
            ControlCenterPanel panel = controls(a);
            View detail = panel == null ? null : (View) field(panel, "detail");
            return panel != null && panel.isDetailVisible(key)
                    && !(Boolean) field(panel, "sliding") && detail != null
                    && detail.getWidth() > 0 && within(a.getCurrentFocus(), detail)
                    ? detail : null;
        });
    }

    private static ActivityScenario<LauncherActivity> launch() {
        ActivityScenario<LauncherActivity> scenario = ActivityScenario.launch(LauncherActivity.class);
        try {
            await(scenario, "settled landscape launcher", a -> {
                View root = a.findViewById(android.R.id.content);
                View anchor = (View) field(a, "mapperBtnView");
                return a.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE
                        && !a.isChangingConfigurations() && a.hasWindowFocus()
                        && root != null && root.getWidth() > root.getHeight()
                        && anchor != null && anchor.isShown()
                        && !((AtomicBoolean) field(a, "appsLoading")).get() ? a : null;
            });
            return scenario;
        } catch (RuntimeException | Error error) {
            scenario.close();
            throw error;
        }
    }

    private static void held(LauncherActivity a, int key, int repeats, View scope) {
        long downTime = SystemClock.uptimeMillis();
        for (int i = 0; i <= repeats; i++) {
            dispatch(a, downTime, KeyEvent.ACTION_DOWN, key, i, InputDevice.SOURCE_DPAD);
            assertFocusWithin(a, scope);
        }
        dispatch(a, downTime, KeyEvent.ACTION_UP, key, 0, InputDevice.SOURCE_DPAD);
        assertFocusWithin(a, scope);
    }

    private static void press(LauncherActivity a, int key) {
        long downTime = SystemClock.uptimeMillis();
        dispatch(a, downTime, KeyEvent.ACTION_DOWN, key, 0);
        dispatch(a, downTime, KeyEvent.ACTION_UP, key, 0);
    }

    private static void dispatch(LauncherActivity a, long downTime, int action, int key, int repeat) {
        dispatch(a, downTime, action, key, repeat, InputDevice.SOURCE_KEYBOARD);
    }

    private static void dispatch(LauncherActivity a, long downTime, int action, int key,
                                 int repeat, int source) {
        a.dispatchKeyEvent(new KeyEvent(downTime, SystemClock.uptimeMillis(), action,
                key, repeat, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                source));
    }

    private static void assertFocusWithin(LauncherActivity a, View scope) {
        assertNotNull("scope exists", scope);
        assertTrue("scope is shown", scope.isShown());
        View focus = a.getCurrentFocus();
        assertNotNull("activity has focus", focus);
        assertTrue("focused view is visible", focus.isShown());
        assertTrue("focus stays inside " + scope + ", actual " + focus, within(focus, scope));
    }

    private static boolean within(View view, View scope) {
        for (View current = view; current != null;) {
            if (current == scope) return true;
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private static ControlCenterPanel controls(LauncherActivity a) {
        return (ControlCenterPanel) field(a, "controlCenter");
    }

    private static <T> T await(ActivityScenario<LauncherActivity> scenario, String description,
                               Function<LauncherActivity, T> probe) {
        long deadline = SystemClock.uptimeMillis() + TIMEOUT_MS;
        do {
            getInstrumentation().waitForIdleSync();
            AtomicReference<T> result = new AtomicReference<>();
            scenario.onActivity(a -> result.set(probe.apply(a)));
            if (result.get() != null) return result.get();
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("Timed out waiting for " + description);
        return null;
    }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static void invoke(Object target, String name, Class<?>[] types, Object... args) {
        try {
            Method method = target.getClass().getDeclaredMethod(name, types);
            method.setAccessible(true);
            method.invoke(target, args);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }
}
