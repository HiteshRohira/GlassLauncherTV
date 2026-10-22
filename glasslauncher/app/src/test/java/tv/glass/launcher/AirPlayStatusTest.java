package tv.glass.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** JVM unit tests for the AirPlay pill / banner rules in {@link AirPlayStatus}. */
public class AirPlayStatusTest {

    private static final long GRACE = 60_000L;

    private static AirPlayStatus awakeAt(long now) {
        AirPlayStatus s = new AirPlayStatus();
        s.onInUse(now, GRACE);
        return s;
    }

    @Test public void advertisedReceiver_isReady() {
        AirPlayStatus s = awakeAt(0);
        assertFalse(s.onCheck(1_000, true, true));
        assertEquals(AirPlayStatus.State.READY, s.state());
    }

    @Test public void missesInsideGrace_areStartingWithoutBanner() {
        AirPlayStatus s = awakeAt(0);
        for (int i = 0; i < 5; i++) assertFalse(s.onCheck(1_000 + i, true, false));
        assertEquals(AirPlayStatus.State.STARTING, s.state());
    }

    @Test public void repeatedMissesAfterGrace_failAndAlertOnce() {
        AirPlayStatus s = awakeAt(0);
        assertFalse(s.onCheck(GRACE + 1, true, false));
        assertTrue(s.onCheck(GRACE + 2, true, false));
        assertEquals(AirPlayStatus.State.FAILED, s.state());
        assertFalse(s.onCheck(GRACE + 3, true, false));
        assertFalse(s.onCheck(GRACE + 4, true, false));
    }

    @Test public void singleMissFromReady_isTolerated() {
        AirPlayStatus s = awakeAt(0);
        s.onCheck(GRACE + 1, true, true);
        assertFalse(s.onCheck(GRACE + 2, true, false));
        assertEquals(AirPlayStatus.State.READY, s.state());
    }

    @Test public void recovery_endsEpisodeSoNextFailureAlertsAgain() {
        AirPlayStatus s = awakeAt(0);
        s.onCheck(GRACE + 1, true, false);
        assertTrue(s.onCheck(GRACE + 2, true, false));
        s.onCheck(GRACE + 3, true, true);
        assertEquals(AirPlayStatus.State.READY, s.state());
        s.onCheck(GRACE + 4, true, false);
        assertTrue(s.onCheck(GRACE + 5, true, false));
    }

    @Test public void screensaver_suppressesBanner() {
        AirPlayStatus s = awakeAt(0);
        s.onNotInUse();
        s.onCheck(GRACE + 1, true, false);
        assertFalse(s.onCheck(GRACE + 2, true, false));
        assertEquals(AirPlayStatus.State.FAILED, s.state());
    }

    @Test public void wake_startsFreshGraceAndEpisode() {
        AirPlayStatus s = awakeAt(0);
        s.onCheck(GRACE + 1, true, false);
        assertTrue(s.onCheck(GRACE + 2, true, false));
        s.onNotInUse();
        long wake = 10 * GRACE;
        s.onInUse(wake, GRACE);
        assertEquals(AirPlayStatus.State.STARTING, s.state());
        assertFalse(s.onCheck(wake + 1, true, false));
        s.onCheck(wake + GRACE + 1, true, false);
        assertTrue(s.onCheck(wake + GRACE + 2, true, false));
    }

    @Test public void uninstalledReceiver_isOffAndNeverAlerts() {
        AirPlayStatus s = awakeAt(0);
        for (int i = 0; i < 4; i++) assertFalse(s.onCheck(GRACE + i, false, false));
        assertEquals(AirPlayStatus.State.OFF, s.state());
    }

    @Test public void parse_roundTripsAndDefaults() {
        for (AirPlayStatus.State v : AirPlayStatus.State.values())
            assertEquals(v, AirPlayStatus.parse(v.name() + "\n"));
        assertEquals(AirPlayStatus.State.STARTING, AirPlayStatus.parse(null));
        assertEquals(AirPlayStatus.State.STARTING, AirPlayStatus.parse("bogus"));
    }
}
