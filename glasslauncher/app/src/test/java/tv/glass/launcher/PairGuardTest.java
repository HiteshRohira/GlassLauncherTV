package tv.glass.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PairGuardTest {
    private long now = 1_000_000L;
    private final PairGuard guard = new PairGuard(() -> now);

    @Test public void fiveDistinctWrongCodesBlockOneAddressForAMinute() {
        for (int i = 0; i < PairGuard.PER_IP_FAILURES; i++) {
            assertFalse(guard.blocked("10.0.0.5"));
            guard.fail("10.0.0.5", "00000" + i);
        }
        assertTrue(guard.blocked("10.0.0.5"));
        assertFalse("other phones are unaffected", guard.blocked("10.0.0.6"));
        now += PairGuard.PER_IP_WINDOW_MS + 1;
        assertFalse(guard.blocked("10.0.0.5"));
    }

    @Test public void repeatingTheSameWrongCodeCountsOnce() {
        // An old Home Screen icon resends its retired code on every launch.
        for (int i = 0; i < 50; i++) guard.fail("10.0.0.5", "123456");
        assertFalse(guard.blocked("10.0.0.5"));
        assertFalse(guard.lockedOut());
    }

    @Test public void manyAddressesTriggerTheGlobalLockout() {
        boolean started = false;
        for (int i = 0; i < PairGuard.GLOBAL_FAILURES; i++) started |= guard.fail("10.0.1." + i, "11111" + (i % 10));
        assertTrue("the guess that crosses the limit reports the lockout", started);
        assertTrue(guard.lockedOut());
        assertTrue("nobody can try a code during the lockout", guard.blocked("10.0.9.9"));
        now += PairGuard.LOCKOUT_MS + 1;
        assertFalse(guard.lockedOut());
        assertFalse(guard.blocked("10.0.9.9"));
    }

    @Test public void lockoutIsReportedOnlyOnce() {
        int reports = 0;
        for (int i = 0; i < PairGuard.GLOBAL_FAILURES * 3; i++) if (guard.fail("10.0.2." + i, "2222" + (10 + i % 90))) reports++;
        assertTrue(reports == 1);
    }

    @Test public void failuresOutsideTheGlobalWindowExpire() {
        for (int i = 0; i < PairGuard.GLOBAL_FAILURES - 1; i++) guard.fail("10.0.3." + i, "33333" + i);
        now += PairGuard.GLOBAL_WINDOW_MS + 1;
        assertFalse(guard.fail("10.0.4.1", "444444"));
        assertFalse(guard.lockedOut());
    }

    @Test public void successClearsTheAddressAndResetClearsEverything() {
        for (int i = 0; i < PairGuard.PER_IP_FAILURES; i++) guard.fail("10.0.0.5", "55555" + i);
        guard.succeed("10.0.0.5");
        assertFalse(guard.blocked("10.0.0.5"));
        for (int i = 0; i < PairGuard.GLOBAL_FAILURES; i++) guard.fail("10.0.5." + i, "66666" + i);
        assertTrue(guard.lockedOut());
        guard.reset();
        assertFalse(guard.lockedOut());
    }

    @Test public void bruteForceBudgetIsTiny() {
        // One attacker rotating addresses gets GLOBAL_FAILURES guesses per
        // lockout cycle: far below the old ~600 guesses a minute.
        int guesses = 0, code = 0;
        long end = now + 24 * 60 * 60_000L;
        while (now < end) {
            String ip = "10.1." + (code / 250) % 250 + "." + code % 250;
            if (!guard.blocked(ip)) { guard.fail(ip, String.format("%06d", code)); guesses++; }
            code++;
            now += 1_000L;
        }
        assertTrue("guesses per day: " + guesses, guesses < 1_000);
    }
}
