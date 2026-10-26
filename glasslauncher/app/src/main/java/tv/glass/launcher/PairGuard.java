package tv.glass.launcher;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Brute-force budget for the phone remote's pairing code. Every place that
 * can reveal whether a code is right ({@code POST /api/pair}, {@code /?pair=}
 * and {@code /manifest.webmanifest?pair=}) shares this one budget.
 *
 * <p>Only distinct wrong guesses count, so an old Home Screen icon that keeps
 * sending a retired code cannot lock out the household. Each address may make
 * {@link #PER_IP_FAILURES} wrong guesses per {@link #PER_IP_WINDOW_MS}; across
 * all addresses, {@link #GLOBAL_FAILURES} wrong guesses inside
 * {@link #GLOBAL_WINDOW_MS} close pairing for {@link #LOCKOUT_MS}.
 */
final class PairGuard {
    interface Clock { long now(); }

    static final int PER_IP_FAILURES = 5;
    static final long PER_IP_WINDOW_MS = 60_000L;
    static final int GLOBAL_FAILURES = 10;
    static final long GLOBAL_WINDOW_MS = 10 * 60_000L;
    static final long LOCKOUT_MS = 15 * 60_000L;
    private static final int MAX_PEERS = 256;
    private static final int MAX_GUESSES_PER_PEER = 16;

    private static final class Peer {
        long windowStart;
        int failures;
        final Set<String> guesses = new LinkedHashSet<>();
    }

    private final Clock clock;
    private final Map<String, Peer> peers = new HashMap<>();
    private final ArrayDeque<Long> recent = new ArrayDeque<>();
    private long lockedUntil;

    PairGuard(Clock clock) { this.clock = clock; }

    /** True while this address, or pairing as a whole, must not try a code. */
    synchronized boolean blocked(String ip) {
        long now = clock.now();
        if (now < lockedUntil) return true;
        Peer p = peers.get(ip);
        return p != null && now - p.windowStart <= PER_IP_WINDOW_MS && p.failures >= PER_IP_FAILURES;
    }

    synchronized boolean lockedOut() { return clock.now() < lockedUntil; }

    /** Records a wrong guess. Returns true when this guess started a lockout. */
    synchronized boolean fail(String ip, String guess) {
        long now = clock.now();
        Peer p = peers.get(ip);
        if (p == null) {
            if (peers.size() >= MAX_PEERS) prune(now);
            // Still full: count the guess globally without tracking the peer.
            if (peers.size() < MAX_PEERS) { p = new Peer(); p.windowStart = now; peers.put(ip, p); }
        }
        if (p != null) {
            if (now - p.windowStart > PER_IP_WINDOW_MS) { p.windowStart = now; p.failures = 0; p.guesses.clear(); }
            if (!p.guesses.add(guess)) return false;
            if (p.guesses.size() > MAX_GUESSES_PER_PEER) p.guesses.remove(p.guesses.iterator().next());
            p.failures++;
        }
        while (!recent.isEmpty() && now - recent.peekFirst() > GLOBAL_WINDOW_MS) recent.pollFirst();
        recent.addLast(now);
        if (recent.size() >= GLOBAL_FAILURES && now >= lockedUntil) {
            lockedUntil = now + LOCKOUT_MS;
            recent.clear();
            return true;
        }
        return false;
    }

    synchronized void succeed(String ip) { peers.remove(ip); }

    /** Forget every failure, e.g. after the code was replaced. */
    synchronized void reset() { peers.clear(); recent.clear(); lockedUntil = 0; }

    private void prune(long now) {
        peers.entrySet().removeIf(e -> now - e.getValue().windowStart > PER_IP_WINDOW_MS);
    }
}
