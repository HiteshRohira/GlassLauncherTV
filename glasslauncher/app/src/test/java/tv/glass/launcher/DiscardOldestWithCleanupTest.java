package tv.glass.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class DiscardOldestWithCleanupTest {
    private final CountDownLatch release = new CountDownLatch(1);
    private ThreadPoolExecutor executor;
    private final List<String> ran = new ArrayList<>(), cleaned = new ArrayList<>();

    @Before public void setUp() throws Exception {
        executor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(2), new DiscardOldestWithCleanup());
        CountDownLatch started = new CountDownLatch(1);
        executor.execute(() -> { started.countDown(); try { release.await(); } catch (InterruptedException ignored) { } });
        started.await();   // the single worker is now busy
    }

    @After public void tearDown() { release.countDown(); executor.shutdownNow(); }

    private DiscardOldestWithCleanup.Task task(String name) {
        return new DiscardOldestWithCleanup.Task(() -> { synchronized (ran) { ran.add(name); } }, () -> cleaned.add(name));
    }

    @Test public void fullQueueDropsTheOldestTaskAndRunsItsCleanup() throws Exception {
        executor.execute(task("a"));
        executor.execute(task("b"));
        executor.execute(task("c"));   // queue full: "a" goes
        assertEquals(List.of("a"), cleaned);
        release.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(List.of("b", "c"), ran);
    }

    @Test public void userTasksAreNeverDiscarded() throws Exception {
        executor.execute(() -> { synchronized (ran) { ran.add("save"); } });
        executor.execute(task("warm"));
        executor.execute(task("newer"));   // discards "warm", not "save"
        assertEquals(List.of("warm"), cleaned);
        release.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(List.of("save", "newer"), ran);
    }

    @Test public void plainTaskIsRefusedLoudlyWhenNothingCanGo() {
        executor.execute(() -> { });
        executor.execute(() -> { });
        try { executor.execute(() -> { }); fail("expected rejection"); }
        catch (RejectedExecutionException expected) { }
        executor.execute(task("x"));   // a discardable task is refused quietly with cleanup
        assertEquals(List.of("x"), cleaned);
    }

    @Test public void afterShutdownTasksCleanUp() {
        executor.shutdown();
        executor.execute(task("late"));
        assertEquals(List.of("late"), cleaned);
    }
}
