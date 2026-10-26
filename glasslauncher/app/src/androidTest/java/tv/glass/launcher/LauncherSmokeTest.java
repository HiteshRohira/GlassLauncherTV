package tv.glass.launcher;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.res.Configuration;
import android.os.SystemClock;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Smoke test that boots {@link LauncherActivity} on an Android device or
 * emulator and verifies the basic UI scaffolding is laid out.
 *
 * <p>Intentionally minimal — this is the safety-net the project lacked, not
 * a full UI test suite. It checks:
 * <ul>
 *     <li>The activity reaches RESUMED without crashing.</li>
 *     <li>The content view tree exists and has a non-zero size after layout.</li>
 *     <li>No leaked window state that would prevent finish() from completing.</li>
 * </ul>
 *
 * <p>This test requires an emulator/device and a separate instrumentation
 * task. Release assembly and host JVM tests do not compile or run it.
 *
 * <h3>Why ActivityScenario, not ActivityTestRule</h3>
 * The previous implementation used {@code ActivityTestRule} (deprecated
 * since androidx.test 1.4) and a {@code Thread.sleep(500)} to hand-wave
 * the layout pass. Both were flake sources on the slow API-29 KVM
 * emulator we run in CI:
 * <ul>
 *     <li>{@code ActivityTestRule} starts the activity before
 *         {@code @Before} hooks complete and tears it down via deprecated
 *         lifecycle paths that occasionally race with the JUnit runner.</li>
 *     <li>{@code Thread.sleep} is a guess. On a cold KVM emulator the
 *         first measure pass can take longer than 500 ms, producing a
 *         flake; on a fast device the same 500 ms is wasted.</li>
 * </ul>
 * {@code ActivityScenario} is the modern recommended primitive. The test drains
 * the main looper, then checks the observable root dimensions until the final
 * landscape activity completes layout. This avoids treating an idle queue or
 * the transient portrait instance as proof that startup has finished on a cold
 * emulator. The test compiles against {@code androidx.test.core} only, so the
 * {@code androidx.test:rules} dependency is no longer needed.
 */
@RunWith(AndroidJUnit4.class)
@LargeTest
public class LauncherSmokeTest {

    private static final long WAIT_TIMEOUT_MS = 8_000L;

    @Test
    public void defaultHome_showsBottomFavoritesAndHidesGrid() {
        try (ActivityScenario<LauncherActivity> scenario =
                     ActivityScenario.launch(LauncherActivity.class)) {
            awaitLaidOutContentView(scenario);

            scenario.onActivity(a -> {
                View root = a.findViewById(android.R.id.content);
                View favorites = a.findViewById(R.id.favorites_bar);
                View grid = a.findViewById(R.id.at4k_home_grid);

                assertNotNull("favorites bar present", favorites);
                assertNotNull("lower app grid present", grid);
                assertEquals("favorites visible by default", View.VISIBLE, favorites.getVisibility());
                assertEquals("lower grid hidden by default", View.GONE, grid.getVisibility());
                assertTrue("favorites bar stays in lower half", favorites.getTop() > root.getHeight() / 2);
            });
        }
    }

    @Test
    public void boots_andHasContentView() {
        try (ActivityScenario<LauncherActivity> scenario =
                     ActivityScenario.launch(LauncherActivity.class)) {
            awaitLaidOutContentView(scenario);
        }
    }

    /** Wait for the final landscape layout; API 26 recreates the first portrait activity. */
    private static View awaitLaidOutContentView(
            ActivityScenario<LauncherActivity> scenario) {
        long deadline = SystemClock.uptimeMillis() + WAIT_TIMEOUT_MS;
        do {
            getInstrumentation().waitForIdleSync();
            AtomicReference<View> result = new AtomicReference<>();
            scenario.onActivity(activity -> {
                assertNotNull("LauncherActivity should be created", activity);
                View root = activity.findViewById(android.R.id.content);
                assertNotNull("content view present", root);
                if (activity.getResources().getConfiguration().orientation
                        == Configuration.ORIENTATION_LANDSCAPE
                        && root.getWidth() > root.getHeight()
                        && root.getHeight() > 0
                        && !activity.isChangingConfigurations()) {
                    result.set(root);
                }
            });
            if (result.get() != null) return result.get();
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("Timed out waiting for laid-out content view");
        return null;
    }
}
