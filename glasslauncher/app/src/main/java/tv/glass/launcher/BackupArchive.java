package tv.glass.launcher;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * The backup file: a zip holding {@code settings.txt} (the {@link SettingsBackup}
 * text, unchanged) and one {@code icons/<identity>.webp} entry per custom icon.
 * The app or TV-input identity is URL-encoded, so an entry name never holds a
 * slash after {@code icons/}.
 *
 * <p>{@link #readAny} also accepts a backup from before the zip format, which is
 * the settings text on its own. Reading never turns an entry name into a file
 * path: the caller hands each icon's bytes to
 * {@link CustomIconStore#save(byte[], String)}, which validates the identity and
 * re-encodes the image. Every byte read is charged to fixed limits, so a corrupt
 * or hostile archive is refused after a bounded amount of work.
 *
 * <p>Android-free, so the format is covered by JVM unit tests.
 */
final class BackupArchive {

    private BackupArchive() { /* no instances */ }

    static final String SETTINGS_ENTRY = "settings.txt";
    static final String ICON_PREFIX = "icons/";
    static final String ICON_SUFFIX = ".webp";

    static final int MAX_SETTINGS_BYTES = 1_000_000;
    static final int MAX_ICON_BYTES = 4 * 1024 * 1024;
    /** Every icon is held in memory until the settings validate, so the total
     *  stays well inside a TV box's heap. */
    static final Limits LIMITS =
            new Limits(MAX_SETTINGS_BYTES, MAX_ICON_BYTES, 1024, 32L * 1024 * 1024);

    private static final byte[] ZIP_MAGIC = {'P', 'K', 3, 4};

    /** Supplies a stored icon's bytes to {@link #write}, or null when there is none. */
    interface IconBytes {
        byte[] read(String identity) throws IOException;
    }

    /** One custom icon read back from an archive. */
    static final class Icon {
        final String identity;
        final byte[] data;

        Icon(String identity, byte[] data) {
            this.identity = identity;
            this.data = data;
        }
    }

    /** A recognised backup: the settings text plus any icons it carried. */
    static final class Contents {
        final String settings;
        final List<Icon> icons;

        Contents(String settings, List<Icon> icons) {
            this.settings = settings;
            this.icons = icons;
        }
    }

    /** Limits applied while reading. {@code totalBytes} caps the raw archive, and
     *  separately the decompressed bytes of all its entries combined. */
    static final class Limits {
        final int settingsBytes;
        final int iconBytes;
        final int entries;
        final long totalBytes;

        Limits(int settingsBytes, int iconBytes, int entries, long totalBytes) {
            this.settingsBytes = settingsBytes;
            this.iconBytes = iconBytes;
            this.entries = entries;
            this.totalBytes = totalBytes;
        }
    }

    /**
     * Write the archive: the settings first, then every identity that has an
     * icon. Icons are stored uncompressed because WebP already is compressed.
     * Returns how many icons were written. Closes {@code out}.
     */
    static int write(OutputStream out, String settings, Collection<String> identities,
                     IconBytes icons) throws IOException {
        int written = 0;
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(out))) {
            zip.putNextEntry(new ZipEntry(SETTINGS_ENTRY));
            zip.write(settings.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (String identity : new LinkedHashSet<>(identities)) {
                if (identity == null) continue;
                byte[] data = icons.read(identity);
                if (data == null || data.length == 0) continue;
                ZipEntry entry = new ZipEntry(entryName(identity));
                CRC32 crc = new CRC32();
                crc.update(data);
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(data.length);
                entry.setCompressedSize(data.length);
                entry.setCrc(crc.getValue());
                zip.putNextEntry(entry);
                zip.write(data);
                zip.closeEntry();
                written++;
            }
        }
        return written;
    }

    /** Read a zip backup or a legacy text backup with the default limits. */
    static Contents readAny(InputStream in) throws IOException {
        return readAny(in, LIMITS);
    }

    /**
     * Read a zip backup or a legacy text backup. Returns null when the input is
     * not a backup this version understands or breaks a limit. Whether the
     * settings text itself is valid is left to {@link SettingsBackup#parse}.
     */
    static Contents readAny(InputStream raw, Limits limits) throws IOException {
        BufferedInputStream in = new BufferedInputStream(raw);
        in.mark(ZIP_MAGIC.length);
        byte[] head = new byte[ZIP_MAGIC.length];
        int length = 0;
        while (length < head.length) {
            int n = in.read(head, length, head.length - length);
            if (n < 0) break;
            length += n;
        }
        in.reset();
        if (!isZip(head, length)) {
            byte[] text = readCapped(in, limits.settingsBytes);
            if (text == null) return null;
            return new Contents(new String(text, StandardCharsets.UTF_8),
                    Collections.emptyList());
        }
        try {
            return readZip(in, limits);
        } catch (LimitExceededException | ZipException | EOFException
                 | IllegalArgumentException notABackup) {
            return null;
        }
    }

    static boolean isZip(byte[] head, int length) {
        if (head == null || length < ZIP_MAGIC.length) return false;
        for (int i = 0; i < ZIP_MAGIC.length; i++) {
            if (head[i] != ZIP_MAGIC[i]) return false;
        }
        return true;
    }

    static String entryName(String identity) {
        String encoded;
        try {
            encoded = URLEncoder.encode(identity, "UTF-8");
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);   // UTF-8 is always supported
        }
        String name = ICON_PREFIX + encoded + ICON_SUFFIX;
        // Android 14+ refuses to read an entry whose name contains "..".
        if (name.contains("..")) name = ICON_PREFIX + encoded.replace(".", "%2E") + ICON_SUFFIX;
        return name;
    }

    /** The identity an icon entry was written for, or null for any other entry. */
    static String identityOf(String entryName) {
        if (entryName == null || !entryName.startsWith(ICON_PREFIX)
                || !entryName.endsWith(ICON_SUFFIX)) {
            return null;
        }
        String encoded = entryName.substring(
                ICON_PREFIX.length(), entryName.length() - ICON_SUFFIX.length());
        if (encoded.isEmpty() || encoded.indexOf('/') >= 0) return null;
        try {
            String identity = URLDecoder.decode(encoded, "UTF-8");
            return identity.isEmpty() ? null : identity;
        } catch (UnsupportedEncodingException | IllegalArgumentException malformed) {
            return null;
        }
    }

    private static Contents readZip(InputStream in, Limits limits) throws IOException {
        Budget budget = new Budget(limits.totalBytes);
        String settings = null;
        Map<String, byte[]> icons = new LinkedHashMap<>();
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new CappedInputStream(in, limits.totalBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > limits.entries) return null;
                String name = entry.getName();
                if (SETTINGS_ENTRY.equals(name)) {
                    if (settings != null) return null;
                    byte[] text = readEntry(zip, limits.settingsBytes, budget);
                    if (text == null) return null;
                    settings = new String(text, StandardCharsets.UTF_8);
                    continue;
                }
                // Unknown entries are read too, so their decompressed bytes count
                // against the budget; skipping one would inflate it uncounted.
                String identity = identityOf(name);
                byte[] data = readEntry(zip, identity != null ? limits.iconBytes : 0, budget);
                if (identity != null && data != null && data.length > 0) {
                    icons.put(identity, data);
                }
            }
        }
        if (settings == null) return null;
        List<Icon> list = new ArrayList<>(icons.size());
        for (Map.Entry<String, byte[]> icon : icons.entrySet()) {
            list.add(new Icon(icon.getKey(), icon.getValue()));
        }
        return new Contents(settings, list);
    }

    /** Read the rest of the current entry. Returns null when it is longer than
     *  {@code maxBytes}; the remainder is still read and charged to the budget. */
    private static byte[] readEntry(ZipInputStream zip, int maxBytes, Budget budget)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        boolean tooLong = false;
        int n;
        while ((n = zip.read(buffer)) != -1) {
            budget.spend(n);
            if (tooLong) continue;
            if (out.size() + n > maxBytes) {
                tooLong = true;
                continue;
            }
            out.write(buffer, 0, n);
        }
        return tooLong ? null : out.toByteArray();
    }

    private static byte[] readCapped(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (out.size() + n > maxBytes) return null;
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static final class LimitExceededException extends IOException {
        LimitExceededException() {
            super("backup is larger than its limits");
        }
    }

    private static final class Budget {
        private long remaining;

        Budget(long bytes) {
            remaining = bytes;
        }

        void spend(long bytes) throws LimitExceededException {
            remaining -= bytes;
            if (remaining < 0) throw new LimitExceededException();
        }
    }

    /** Counts the raw archive bytes and stops once they pass the limit. */
    private static final class CappedInputStream extends FilterInputStream {
        private final Budget budget;

        CappedInputStream(InputStream in, long limit) {
            super(in);
            budget = new Budget(limit);
        }

        @Override public int read() throws IOException {
            int b = super.read();
            if (b >= 0) budget.spend(1);
            return b;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) budget.spend(n);
            return n;
        }

        @Override public long skip(long n) throws IOException {
            long skipped = super.skip(n);
            if (skipped > 0) budget.spend(skipped);
            return skipped;
        }

        @Override public boolean markSupported() {
            return false;
        }
    }
}
