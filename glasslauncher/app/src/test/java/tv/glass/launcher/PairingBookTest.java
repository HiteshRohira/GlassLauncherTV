package tv.glass.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PairingBookTest {
    private int n;
    private final PairingBook.Random random = new PairingBook.Random() {
        @Override public String token() { return "token-" + (n++); }
        @Override public String pin() { return String.format("%06d", 100000 + (n++)); }
    };

    @Test public void pairingGivesEachPhoneItsOwnTokenAndANewCode() {
        PairingBook b = new PairingBook("111111");
        String first = b.pair(random, 0);
        String codeAfterFirst = b.pin;
        assertNotEquals("111111", codeAfterFirst);
        String second = b.pair(random, 0);
        assertNotEquals(first, second);
        assertNotEquals(codeAfterFirst, b.pin);
        assertTrue("an earlier phone stays connected", b.isDevice(first));
        assertTrue(b.isDevice(second));
        assertFalse(b.isDevice("nope"));
        assertEquals(2, b.deviceCount());
    }

    @Test public void installTokenIsSingleUseAndExpires() {
        PairingBook b = new PairingBook("111111");
        b.pair(random, 0);
        String install = b.newInstall(random, 0);
        assertTrue(b.installValid(install, 1000));
        String homeScreen = b.redeem(install, random, 1000);
        assertTrue(b.isDevice(homeScreen));
        assertNull("single use", b.redeem(install, random, 2000));

        String late = b.newInstall(random, 0);
        assertNull("expired", b.redeem(late, random, PairingBook.INSTALL_TTL_MS + 1));
    }

    @Test public void redeemingDoesNotChangeTheCode() {
        PairingBook b = new PairingBook("111111");
        b.pair(random, 0);
        String code = b.pin;
        b.redeem(b.newInstall(random, 0), random, 1);
        assertEquals(code, b.pin);
    }

    @Test public void forgetAllRevokesEverything() {
        PairingBook b = new PairingBook("111111");
        String phone = b.pair(random, 0);
        String install = b.newInstall(random, 0);
        String code = b.pin;
        b.forgetAll(random);
        assertFalse(b.isDevice(phone));
        assertNull(b.redeem(install, random, 1));
        assertNotEquals(code, b.pin);
        assertEquals(0, b.deviceCount());
    }

    @Test public void deviceListIsBounded() {
        PairingBook b = new PairingBook("111111");
        String oldest = b.pair(random, 0);
        for (int i = 0; i < PairingBook.MAX_DEVICES; i++) b.pair(random, i);
        assertEquals(PairingBook.MAX_DEVICES, b.deviceCount());
        assertFalse("oldest pairing is dropped first", b.isDevice(oldest));
    }
}
