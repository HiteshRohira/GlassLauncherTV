package tv.glass.launcher;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/** Native Feather menu material, adaptive layout, and Control Center reachability. */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class EdgeGlassMenuTest {

    private static final long WAIT_TIMEOUT_MS = 8_000L;

    @Test
    public void perAppMenu_usesFeatherGlass_andExposesAcceptedActions() {
        try (ActivityScenario<LauncherActivity> scenario = launchSettledLauncher()) {
            View homeCell = focusHomeCell(scenario);
            scenario.onActivity(a -> assertTrue(homeCell.performLongClick()));

            // Reachability: the menu overlay becomes visible and its rows exist.
            View overlay = awaitVisibleViewField(scenario, "menuOverlay");
            assertNotNull("context menu opens", overlay);
            scenario.onActivity(a -> {
                for (String name : new String[]{"menuMove", "menuHide", "menuAppInfo"}) {
                    assertTrue(name + " is reachable", ((View) field(a, name)).isShown());
                    assertTrue(name + " has an action", ((View) field(a, name)).isClickable());
                }
                for (String name : new String[]{"menuRename", "menuChangeIcon",
                        "menuResetIcon", "menuUninstall"}) {
                    assertEquals(name + " deliberately absent from Feather menu",
                            View.GONE, ((View) field(a, name)).getVisibility());
                }

                // Feather uses the native glass gradient, with rounded corners.
                LinearLayout col = firstLinearLayoutChild((ViewGroup) overlay);
                assertNotNull("menu column present", col);
                assertFeatherPanel(col, NativeTheme.px(a, 22));

                float density = a.getResources().getDisplayMetrics().density;
                assertEquals("menu column min width is the shared floor, not 140dp",
                        EdgeGlassStyle.dp(density, EdgeGlassStyle.MENU_MIN_WIDTH_DP),
                        col.getMinimumWidth());
            });
        }
    }

    @Test
    public void perAppMenu_itemsAreAdaptiveWidth_notFixed140() {
        try (ActivityScenario<LauncherActivity> scenario = launchSettledLauncher()) {
            View homeCell = focusHomeCell(scenario);
            scenario.onActivity(a -> assertTrue(homeCell.performLongClick()));
            View overlay = awaitVisibleViewField(scenario, "menuOverlay");

            scenario.onActivity(a -> {
                float density = a.getResources().getDisplayMetrics().density;
                int hard140 = Math.round(140 * density);
                LinearLayout col = firstLinearLayoutChild((ViewGroup) overlay);
                assertNotNull(col);
                assertTrue("menu has rows", col.getChildCount() > 0);
                int visibleRows = 0;
                int previousBottom = 0;
                for (int i = 0; i < col.getChildCount(); i++) {
                    View row = col.getChildAt(i);
                    if (row.getVisibility() != View.VISIBLE) continue;
                    visibleRows++;
                    LinearLayout.LayoutParams lp =
                            (LinearLayout.LayoutParams) row.getLayoutParams();
                    assertEquals("row " + i + " fills the adaptive column",
                            ViewGroup.LayoutParams.MATCH_PARENT, lp.width);
                    assertTrue("row " + i + " is not hard-pinned to 140dp", lp.width != hard140);
                    assertTrue("visible rows do not overlap", row.getTop() >= previousBottom);
                    assertTrue("visible row has usable height", row.getHeight() > 0);
                    assertEquals("visible row fills the column's content width",
                            col.getWidth() - col.getPaddingLeft() - col.getPaddingRight(),
                            row.getWidth());
                    previousBottom = row.getBottom();
                }
                assertEquals("Move, Hide and App info are the accepted app actions", 3, visibleRows);
                // The measured menu never collapses below the shared floor.
                assertTrue("menu measured width respects the min floor",
                        col.getWidth() >= EdgeGlassStyle.dp(
                                density, EdgeGlassStyle.MENU_MIN_WIDTH_DP) - 1);
            });
        }
    }

    @Test
    public void settingsCard_isEmbeddedInFeatherSheet_andReturnsToOpeningControl() {
        try (ActivityScenario<LauncherActivity> scenario = launchSettledLauncher()) {
            scenario.onActivity(a -> assertTrue(((View) field(a, "mapperBtnView")).performClick()));
            View overlay = awaitVisibleViewField(scenario, "controlCenter");
            scenario.onActivity(a -> {
                View appearance = overlay.findViewWithTag("appearance");
                assertNotNull("Appearance tile is reachable from Control Center", appearance);
                assertTrue(appearance.performClick());
            });
            awaitValue(scenario, "settled embedded Appearance", a -> {
                ControlCenterPanel panel = (ControlCenterPanel) overlay;
                View card = (View) field(a, "settingsCard");
                return panel.isDetailVisible("appearance") && !panel.isTransitioning()
                        && card != null && card.isShown() && card.hasFocus() ? card : null;
            });
            scenario.onActivity(a -> {
                View sheet = (View) field(overlay, "card");
                assertFeatherPanel(sheet, NativeTheme.px(a, 30));
                assertEquals("native sheet width", NativeTheme.px(a, 440), sheet.getWidth());
                assertTrue("sheet stays within the screen", sheet.getRight() <= overlay.getWidth());
                View settings = (View) field(a, "settingsCard");
                assertTrue("settings content belongs to the visible detail",
                        isDescendant((View) field(overlay, "detail"), settings));
                assertEquals("embedded content has one sheet background", null, settings.getBackground());
                LinearLayout column = (LinearLayout) field(a, "settingsColumn");
                assertEquals("four Appearance categories", 4, column.getChildCount());
                for (int i = 0; i < column.getChildCount(); i++) {
                    assertTrue("category is actionable", column.getChildAt(i).isClickable());
                }
                press(a, KeyEvent.KEYCODE_BACK);
            });
            awaitValue(scenario, "Appearance tile restored after Back", a -> {
                ControlCenterPanel panel = (ControlCenterPanel) overlay;
                View focused = a.getCurrentFocus();
                return panel.isVisible() && !panel.isTransitioning()
                        && !panel.isDetailVisible("appearance") && focused != null
                        && "appearance".equals(focused.getTag()) ? focused : null;
            });
            scenario.onActivity(a -> {
                press(a, KeyEvent.KEYCODE_BACK);
                assertEquals(View.GONE, overlay.getVisibility());
                assertSame("closing restores the toolbar anchor",
                        field(a, "mapperBtnView"), a.getCurrentFocus());
            });
        }
    }

    private static void assertFeatherPanel(View view, int cornerRadius) {
        assertTrue("Feather panel uses a native gradient", view.getBackground() instanceof GradientDrawable);
        GradientDrawable background = (GradientDrawable) view.getBackground();
        assertEquals("rounded native glass", cornerRadius, background.getCornerRadius(), 0.1f);
        int[] colors = background.getColors();
        assertNotNull("glass gradient colors", colors);
        assertEquals("three-stop glass panel", 3, colors.length);
        for (int color : colors) {
            assertTrue("sheet provides readable contrast", Color.alpha(color) >= 220);
            assertTrue("sheet remains translucent", Color.alpha(color) < 255);
        }
    }

    private static boolean isDescendant(View ancestor, View child) {
        for (android.view.ViewParent p = child.getParent(); p != null; p = p.getParent()) {
            if (p == ancestor) return true;
        }
        return false;
    }

    private static void press(LauncherActivity a, int key) {
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
    }

    // ── minimal helpers (self-contained; no shared test infra to weaken) ──

    private static LinearLayout firstLinearLayoutChild(ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View c = parent.getChildAt(i);
            if (c instanceof LinearLayout) return (LinearLayout) c;
        }
        return null;
    }

    private static ActivityScenario<LauncherActivity> launchSettledLauncher() {
        ActivityScenario<LauncherActivity> scenario =
                ActivityScenario.launch(LauncherActivity.class);
        try {
            awaitValue(scenario, "laid-out landscape LauncherActivity", activity -> {
                View root = activity.findViewById(android.R.id.content);
                return activity.getResources().getConfiguration().orientation
                        == Configuration.ORIENTATION_LANDSCAPE
                        && root != null
                        && root.getWidth() > root.getHeight()
                        && !activity.isChangingConfigurations()
                        ? activity : null;
            });
            return scenario;
        } catch (RuntimeException | Error error) {
            scenario.close();
            throw error;
        }
    }

    private static View awaitVisibleViewField(
            ActivityScenario<LauncherActivity> scenario, String fieldName) {
        return awaitValue(scenario, "visible " + fieldName, activity -> {
            View view = (View) field(activity, fieldName);
            return view != null && view.getVisibility() == View.VISIBLE && view.isShown()
                    && view.getWidth() > 0 && view.getHeight() > 0 ? view : null;
        });
    }

    private static View focusHomeCell(ActivityScenario<LauncherActivity> scenario) {
        return awaitValue(scenario, "focused home CellView", activity -> {
            View shelf = (View) field(activity, "shelf");
            if (shelf == null || shelf.getVisibility() != View.VISIBLE || !shelf.isShown()) {
                return null;
            }
            invoke(shelf, "requestFocusOnIndex",
                    new Class<?>[] {int.class, boolean.class}, 0, true);
            View focused = activity.getWindow().getDecorView().findFocus();
            return focused != null
                    && focused.getClass().getSimpleName().equals("CellView")
                    ? focused : null;
        });
    }

    private static <T> T awaitValue(ActivityScenario<LauncherActivity> scenario,
                                    String description,
                                    Function<LauncherActivity, T> probe) {
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MS;
        do {
            getInstrumentation().waitForIdleSync();
            AtomicReference<T> result = new AtomicReference<>();
            scenario.onActivity(activity -> result.set(probe.apply(activity)));
            if (result.get() != null) return result.get();
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("Timed out waiting for " + description);
        return null;
    }

    private static Object field(Object target, String name) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void invoke(Object target, String name) {
        invoke(target, name, new Class<?>[0]);
    }

    private static void invoke(Object target, String name, Class<?>[] parameterTypes,
                               Object... args) {
        try {
            Method m = target.getClass().getDeclaredMethod(name, parameterTypes);
            m.setAccessible(true);
            m.invoke(target, args);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
