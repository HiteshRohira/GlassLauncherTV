package tv.glass.launcher;

/**
 * Pure state machine behind the AirPlay toolbar pill and failure banner.
 *
 * <p>Inputs are the monitor's observations: whether the receiver app is
 * installed and enabled, whether this TV's AirPlay service was found on the
 * local network, and whether the screen is in use (awake and not in a
 * screensaver). It answers two questions: which state the pill shows, and
 * whether a check result should raise the one-shot "AirPlay isn't available"
 * banner.
 *
 * <p>Rules:
 * <ul>
 *   <li>An uninstalled / disabled receiver is OFF — intentional, never alerted.</li>
 *   <li>After boot or wake a grace window lets the receiver and Wi-Fi settle;
 *       misses inside it read as STARTING.</li>
 *   <li>After the grace window, {@link #MISSES_TO_FAIL} consecutive misses
 *       mean FAILED. A single miss from READY is tolerated (mDNS can drop one
 *       answer).</li>
 *   <li>The banner fires once per failure episode and only while the screen
 *       is in use; finding the service again ends the episode.</li>
 * </ul>
 *
 * <p>Android-free so the rules run as JVM unit tests.
 */
final class AirPlayStatus {

    enum State { OFF, STARTING, READY, FAILED }

    static final int MISSES_TO_FAIL = 2;

    private State state = State.STARTING;
    private long graceUntil;
    private int misses;
    private boolean inUse = true;
    private boolean alerted;

    State state() { return state; }

    boolean inUse() { return inUse; }

    /** The screen became usable (boot, wake, screensaver dismissed). Starts a
     *  fresh grace window and failure episode. A READY receiver stays READY
     *  until a check says otherwise. */
    void onInUse(long now, long graceMs) {
        inUse = true;
        graceUntil = now + graceMs;
        misses = 0;
        alerted = false;
        if (state != State.READY && state != State.OFF) state = State.STARTING;
    }

    /** Screen off or screensaver: suppress banners until {@link #onInUse}. */
    void onNotInUse() {
        inUse = false;
    }

    boolean inGrace(long now) { return now < graceUntil; }

    /**
     * Record one check result.
     *
     * @return true when the caller should show the failure banner now.
     */
    boolean onCheck(long now, boolean receiverInstalled, boolean advertised) {
        if (!receiverInstalled) {
            state = State.OFF;
            misses = 0;
            alerted = false;
            return false;
        }
        if (advertised) {
            state = State.READY;
            misses = 0;
            alerted = false;
            return false;
        }
        if (inGrace(now)) {
            // Settling after boot / wake: neither shown as a failure nor
            // counted toward one.
            if (state != State.READY) state = State.STARTING;
            return false;
        }
        misses++;
        if (misses < MISSES_TO_FAIL) {
            if (state == State.OFF) state = State.STARTING;
            return false;
        }
        state = State.FAILED;
        if (inUse && !alerted) {
            alerted = true;
            return true;
        }
        return false;
    }

    /** Parse a persisted state name, defaulting to STARTING. */
    static State parse(String s) {
        if (s != null) {
            for (State v : State.values()) if (v.name().equals(s.trim())) return v;
        }
        return State.STARTING;
    }
}
