package tv.glass.launcher;

import java.util.Iterator;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Queue-full policy for the icon/banner executor. Like DiscardOldestPolicy it
 * makes room for the newest task (the cell the user is looking at), but:
 * <ul>
 * <li>it only discards {@link Task}s, never plain tasks such as saving a
 *     custom icon the user just picked;</li>
 * <li>a discarded or refused task runs its cleanup, so its in-flight entry is
 *     removed and the artwork can be requested again. DiscardOldestPolicy
 *     left those entries behind and the icon stayed a placeholder.</li>
 * </ul>
 * Cleanups run on the submitting thread.
 */
final class DiscardOldestWithCleanup implements RejectedExecutionHandler {
    /** Background work that may be dropped under pressure. */
    static final class Task implements Runnable {
        final Runnable work, onDiscard;
        Task(Runnable work, Runnable onDiscard) { this.work = work; this.onDiscard = onDiscard; }
        @Override public void run() { work.run(); }
    }

    @Override public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
        if (!executor.isShutdown()) {
            for (Iterator<Runnable> it = executor.getQueue().iterator(); it.hasNext(); ) {
                Runnable queued = it.next();
                if (queued instanceof Task && executor.getQueue().remove(queued)) {
                    discarded(queued);
                    if (executor.getQueue().offer(r)) return;
                    break;
                }
            }
        }
        // shut down, or nothing discardable: refuse the new task cleanly
        if (r instanceof Task) discarded(r);
        else throw new java.util.concurrent.RejectedExecutionException("executor full");
    }

    private static void discarded(Runnable r) {
        if (r instanceof Task) {
            try { ((Task) r).onDiscard.run(); } catch (RuntimeException ignored) { }
        }
    }
}
