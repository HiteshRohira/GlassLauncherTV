package tv.glass.launcher;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
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

/**
 * Device-level regressions for the Edge-glass slate restyle of the settings
 * card and the per-app context menu.
 *
 * <p>These assert the NEW visual contract (both surfaces share the
 * {@link EdgeGlassStyle} panel drawable; the menu is adaptive-width, not a
 * hard 140 dp) AND that the restyle did not change reachability — the menu and
 * settings panel still open and still expose their rows. They deliberately add
 * only new assertions; the pre-existing {@code LauncherCustomizationTest}
 * coverage of settings page structure is left untouched.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class EdgeGlassMenuTest {

    private static final long WAIT_TIMEOUT_MS = 8_000L;

    @Test
    public void perAppMenu_usesSharedEdgeGlassPanel_andOpensReachable() {
        try (ActivityScenario<LauncherActivity> scenario = launchSettledLauncher()) {
            View homeCell = focusHomeCell(scenario);
            scenario.onActivity(a -> assertTrue(homeCell.performLongClick()));

            // Reachability: the menu overlay becomes visible and its rows exist.
            View overlay = awaitVisibleViewField(scenario, "menuOverlay");
            assertNotNull("context menu opens", overlay);
            scenario.onActivity(a -> {
                assertNotNull("Hide row present", field(a, "menuHide"));
                assertNotNull("Rename row present", field(a, "menuRename"));
                assertNotNull("Uninstall row present", field(a, "menuUninstall"));

                // The menu column carries the shared edge-glass panel drawable.
                LinearLayout col = firstLinearLayoutChild((ViewGroup) overlay);
                assertNotNull("menu column present", col);
                Drawable bg = col.getBackground();
                assertNotNull("menu has a panel background", bg);
                assertEquals("menu uses the shared EdgeGlassStyle panel",
                        "PanelDrawable", bg.getClass().getSimpleName());

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
                for (int i = 0; i < col.getChildCount(); i++) {
                    View row = col.getChildAt(i);
                    LinearLayout.LayoutParams lp =
                            (LinearLayout.LayoutParams) row.getLayoutParams();
                    // Adaptive: rows fill the column (MATCH_PARENT), never a
                    // hard 140 dp pixel width.
                    assertEquals("row " + i + " width is MATCH_PARENT (adaptive)",
                            ViewGroup.LayoutParams.MATCH_PARENT, lp.width);
                    assertTrue("row " + i + " is not hard-pinned to 140dp",
                            lp.width != hard140);
                    int expectedGap = i == col.getChildCount() - 1
                            ? 0 : EdgeGlassStyle.dp(density, EdgeGlassStyle.ROW_GAP_DP);
                    assertEquals("row " + i + " inter-row gap", expectedGap, lp.bottomMargin);
                }
                // The measured menu never collapses below the shared floor.
                assertTrue("menu measured width respects the min floor",
                        col.getWidth() >= EdgeGlassStyle.dp(
                                density, EdgeGlassStyle.MENU_MIN_WIDTH_DP) - 1);
            });
        }
    }

    @Test
    public void settingsCard_usesSameSharedEdgeGlassPanel_andOpensReachable() {
        try (ActivityScenario<LauncherActivity> scenario = launchSettledLauncher()) {
            scenario.onActivity(a -> invoke(a, "showSettingsPanel"));
            awaitVisibleViewField(scenario, "settingsOverlay");

            scenario.onActivity(a -> {
                View card = (View) field(a, "settingsCard");
                assertNotNull("settings card present", card);
                Drawable bg = card.getBackground();
                assertNotNull("settings card has a panel background", bg);
                assertEquals("settings card uses the same shared EdgeGlassStyle panel",
                        "PanelDrawable", bg.getClass().getSimpleName());

                // Reachability: the row column has rows on the main page.
                LinearLayout col = (LinearLayout) field(a, "settingsColumn");
                assertNotNull("settings column present", col);
                assertTrue("settings has rows", col.getChildCount() > 0);
            });
        }
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
                    ? view : null;
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
