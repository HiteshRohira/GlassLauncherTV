package tv.glass.launcher;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.*;

import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.FrameMetrics;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Repeated real-window transitions, with startup/settle frames excluded.
 * Writes per-run results rather than hiding sporadic stalls in one average.
 * This is an emulator regression benchmark, not a physical-TV certification.
 */
@RunWith(AndroidJUnit4.class)
public class RepeatedUiPerformanceTest {
    @Test public void repeatedTransitions() throws Exception {
        int runs = Integer.parseInt(androidx.test.platform.app.InstrumentationRegistry
                .getArguments().getString("runs", "7"));
        int cycles = Integer.parseInt(androidx.test.platform.app.InstrumentationRegistry
                .getArguments().getString("cycles", "8"));
        JSONArray results = new JSONArray();
        HandlerThread thread = new HandlerThread("mirage-frame-metrics");
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        List<Long> durations = Collections.synchronizedList(new ArrayList<>());
        Window.OnFrameMetricsAvailableListener listener = (window, metrics, dropped) -> {
            if (metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0)
                durations.add(metrics.getMetric(FrameMetrics.TOTAL_DURATION));
        };
        try (ActivityScenario<LauncherActivity> scenario = ActivityScenario.launch(LauncherActivity.class)) {
            await(scenario, a -> a.hasWindowFocus() && described(a.getWindow().getDecorView(), "Control Center", false) != null);
            scenario.onActivity(a -> a.getWindow().addOnFrameMetricsAvailableListener(listener, handler));
            SystemClock.sleep(1200);
            String surfaces = androidx.test.platform.app.InstrumentationRegistry.getArguments()
                    .getString("surfaces", "drawer,Control Center,Bluetooth");
            for (String surface : surfaces.split(",")) {
                for (int run = 0; run < runs; run++) {
                    drain(handler); durations.clear();
                    long start = SystemClock.elapsedRealtime();
                    for (int cycle = 0; cycle < cycles; cycle++) {
                        scenario.onActivity(a -> {
                            if ("drawer".equals(surface)) {
                                View shelf = a.findViewById(R.id.favorites_bar);
                                assertNotNull("app shelf", shelf);
                                View tile = firstFocusable(shelf);
                                assertNotNull("visible app tile", tile);
                                tile.requestFocus();
                                press(a, KeyEvent.KEYCODE_DPAD_DOWN);
                            } else {
                                View action = described(a.getWindow().getDecorView(), surface, false);
                                assertNotNull(surface + " toolbar action", action);
                                action.requestFocus(); action.performClick();
                            }
                        });
                        SystemClock.sleep(350);
                        scenario.onActivity(a -> press(a, KeyEvent.KEYCODE_BACK));
                        SystemClock.sleep(350);
                    }
                    drain(handler);
                    ArrayList<Long> samples;
                    synchronized (durations) { samples = new ArrayList<>(durations); }
                    Collections.sort(samples);
                    assertFalse("window produced measured frames", samples.isEmpty());
                    int slow = 0;
                    for (long ns : samples) if (ns > 16_666_667L) slow++;
                    JSONObject row = new JSONObject().put("surface", surface).put("run", run)
                            .put("cycles", cycles).put("frames", samples.size())
                            .put("over_budget", slow).put("over_budget_pct", 100.0 * slow / samples.size())
                            .put("p50_ms", percentile(samples, .5) / 1e6)
                            .put("p95_ms", percentile(samples, .95) / 1e6)
                            .put("p99_ms", percentile(samples, .99) / 1e6)
                            .put("max_ms", samples.get(samples.size() - 1) / 1e6)
                            .put("wall_ms", SystemClock.elapsedRealtime() - start);
                    results.put(row);
                    Bundle status = new Bundle(); status.putString("stream", "MIRAGE_PERF " + row + "\n");
                    getInstrumentation().sendStatus(0, status);
                }
            }
            scenario.onActivity(a -> a.getWindow().removeOnFrameMetricsAvailableListener(listener));
        } finally {
            thread.quitSafely(); thread.join(3000);
            File destination = new File(getInstrumentation().getTargetContext().getCacheDir(), "repeated-ui-performance.json");
            Files.write(destination.toPath(), results.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }
    private static long percentile(List<Long> values, double q) {
        return values.get(Math.max(0, (int) Math.ceil(values.size() * q) - 1));
    }
    private static void drain(Handler handler) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1); handler.post(done::countDown);
        assertTrue("metrics callback drained", done.await(5, TimeUnit.SECONDS));
    }
    private static View firstFocusable(View view) {
        if (view.isShown() && view.isFocusable() && !(view instanceof ViewGroup)) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View result = firstFocusable(((ViewGroup) view).getChildAt(i)); if (result != null) return result;
        }
        return null;
    }
    private static View described(View view, String description, boolean group) {
        if (view.isShown() && description.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())
                && (view.getWidth() > 500) == group) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View result = described(((ViewGroup) view).getChildAt(i), description, group); if (result != null) return result;
        }
        return null;
    }
    private static void press(LauncherActivity a, int code) {
        long t = SystemClock.uptimeMillis();
        a.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        a.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
    }
    private interface Condition { boolean test(LauncherActivity a); }
    private static void await(ActivityScenario<LauncherActivity> scenario, Condition condition) {
        long until = SystemClock.uptimeMillis() + 10_000;
        AtomicReference<Boolean> ready = new AtomicReference<>(false);
        while (SystemClock.uptimeMillis() < until) {
            scenario.onActivity(a -> ready.set(condition.test(a)));
            if (ready.get()) return;
            SystemClock.sleep(50);
        }
        fail("launcher did not become ready");
    }
}
