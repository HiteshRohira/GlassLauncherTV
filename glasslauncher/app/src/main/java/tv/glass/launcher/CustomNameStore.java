package tv.glass.launcher;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Pure-Java codec and validation for user-defined launcher labels. */
final class CustomNameStore {

    static final int MAX_NAME_CODE_POINTS = 48;
    private static final int MAX_ENTRIES = 256;
    private static final int MAX_IDENTITY_LENGTH = 1024;
    private static final int MAX_SERIALIZED_LENGTH = 64 * 1024;

    private CustomNameStore() { /* no instances */ }

    /** Canonical single-line representation safe inside SharedPreferences and backups. */
    static String serialize(Map<String, String> names) {
        if (names == null || names.isEmpty()) return "";
        ArrayList<String> identities = new ArrayList<>(names.keySet());
        Collections.sort(identities);
        StringBuilder out = new StringBuilder(Math.min(4096, identities.size() * 48));
        int written = 0;
        for (String identity : identities) {
            if (written >= MAX_ENTRIES) break;
            if (!isValidIdentity(identity)) continue;
            String name = sanitize(names.get(identity));
            if (name == null) continue;
            String encodedIdentity = encode(identity);
            String encodedName = encode(name);
            int addedLength = encodedIdentity.length() + encodedName.length() + 1
                    + (out.length() > 0 ? 1 : 0);
            if (out.length() + addedLength > MAX_SERIALIZED_LENGTH) break;
            if (out.length() > 0) out.append(',');
            out.append(encodedIdentity).append(':').append(encodedName);
            written++;
        }
        return out.toString();
    }

    /** Parse a canonical string; malformed entries are ignored independently. */
    static Map<String, String> parse(String raw) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty() || raw.length() > MAX_SERIALIZED_LENGTH) return out;
        String[] entries = raw.split(",", -1);
        for (String entry : entries) {
            if (out.size() >= MAX_ENTRIES) break;
            int separator = entry.indexOf(':');
            if (separator <= 0 || separator == entry.length() - 1) continue;
            try {
                String identity = decode(entry.substring(0, separator));
                String name = sanitize(decode(entry.substring(separator + 1)));
                if (isValidIdentity(identity) && name != null) out.put(identity, name);
            } catch (IllegalArgumentException ignored) {
                // One damaged entry must not discard the remaining user names.
            }
        }
        return out;
    }

    /** Trim, collapse whitespace/control runs, and cap text without splitting surrogates. */
    static String sanitize(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        StringBuilder out = new StringBuilder(Math.min(raw.length(), MAX_NAME_CODE_POINTS));
        boolean pendingSpace = false;
        int written = 0;
        for (int offset = 0; offset < raw.length() && written < MAX_NAME_CODE_POINTS; ) {
            int codePoint = raw.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isISOControl(codePoint)) {
                if (out.length() > 0) pendingSpace = true;
                continue;
            }
            if (pendingSpace) {
                // Keep room for both the separator and the next visible code
                // point. Stopping here is better than returning a trailing
                // space at the length boundary.
                if (written + 1 >= MAX_NAME_CODE_POINTS) break;
                out.append(' ');
                written++;
                pendingSpace = false;
            }
            out.appendCodePoint(codePoint);
            written++;
        }
        return out.length() == 0 ? null : out.toString();
    }

    private static boolean isValidIdentity(String identity) {
        return identity != null && !identity.isEmpty()
                && identity.length() <= MAX_IDENTITY_LENGTH
                && identity.indexOf('\n') < 0 && identity.indexOf('\r') < 0
                && identity.indexOf('\t') < 0;
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }
}
