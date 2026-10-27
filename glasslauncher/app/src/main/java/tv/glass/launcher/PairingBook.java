package tv.glass.launcher;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Phone-remote pairing state, independent of Android so it can be unit tested.
 *
 * <ul>
 * <li>One current six-digit code. Pairing with it gives that phone its own
 *     token and replaces the code, so the next phone needs the new one.</li>
 * <li>Paired phones keep their token (a one-year cookie) until "Forget
 *     paired phones". The same phone never needs the code again.</li>
 * <li>An iOS Home Screen web app has its own cookie jar. After pairing, the
 *     phone gets a one-time install token that the Home Screen app redeems
 *     for its own device token on first launch; it expires after
 *     {@link #INSTALL_TTL_MS}.</li>
 * </ul>
 */
final class PairingBook {
    long revision;
    interface Random { String token(); String pin(); }

    static final int MAX_DEVICES = 16;
    static final int MAX_INSTALLS = 16;
    static final long INSTALL_TTL_MS = 7L * 24 * 60 * 60 * 1000;

    static final class Device { final String token; final long since; Device(String t, long s) { token = t; since = s; } }
    static final class Install { final String token; final long expires; Install(String t, long e) { token = t; expires = e; } }

    String pin;
    final List<Device> devices = new ArrayList<>();
    final List<Install> installs = new ArrayList<>();

    PairingBook(String pin) { this.pin = pin; }

    /** A phone entered the right code: its token, and a new code for the next phone. */
    String pair(Random random, long now) {
        String token = addDevice(random.token(), now);
        pin = random.pin();
        return token;
    }

    /** One-time credential for this phone's Home Screen app. */
    String newInstall(Random random, long now) {
        prune(now);
        while (installs.size() >= MAX_INSTALLS) installs.remove(0);
        String token = random.token();
        installs.add(new Install(token, now + INSTALL_TTL_MS));
        return token;
    }

    boolean installValid(String token, long now) {
        prune(now);
        for (Install i : installs) if (same(i.token, token)) return true;
        return false;
    }

    /** Redeems an install token for a new device token, or null. Single use. */
    String redeem(String token, Random random, long now) {
        prune(now);
        for (Iterator<Install> it = installs.iterator(); it.hasNext(); ) {
            if (same(it.next().token, token)) { it.remove(); return addDevice(random.token(), now); }
        }
        return null;
    }

    boolean isDevice(String token) {
        boolean found = false;
        for (Device d : devices) found |= same(d.token, token);   // no early exit
        return found;
    }

    /** "Forget paired phones": every phone and install link stops working. */
    void forgetAll(Random random) {
        devices.clear();
        installs.clear();
        pin = random.pin();
    }

    int deviceCount() { return devices.size(); }

    private String addDevice(String token, long now) {
        while (devices.size() >= MAX_DEVICES) devices.remove(0);   // oldest pairing goes
        devices.add(new Device(token, now));
        return token;
    }

    private void prune(long now) {
        installs.removeIf(i -> i.expires <= now);
    }

    static boolean same(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
