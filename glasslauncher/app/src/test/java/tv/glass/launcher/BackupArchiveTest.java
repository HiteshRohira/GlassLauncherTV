package tv.glass.launcher;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class BackupArchiveTest {

    private static final String SETTINGS = "GLBK\t2\napp_order\tcom.a,com.b\n";
    private static final String TV_INPUT = "tvinput://com.oem.tv/.HdmiService/HW5";

    @Test public void writeAndRead_roundTripsSettingsAndIcons() throws IOException {
        Map<String, byte[]> stored = new HashMap<>();
        stored.put("com.a", new byte[] {1, 2, 3});
        stored.put(TV_INPUT, new byte[] {4, 5});
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int written = BackupArchive.write(out, SETTINGS,
                Arrays.asList("com.a", "com.b", TV_INPUT, "com.a"), stored::get);

        assertEquals(2, written);
        BackupArchive.Contents contents = BackupArchive.readAny(in(out.toByteArray()));
        assertNotNull(contents);
        assertEquals(SETTINGS, contents.settings);
        assertEquals(2, contents.icons.size());
        assertEquals("com.a", contents.icons.get(0).identity);
        assertArrayEquals(new byte[] {1, 2, 3}, contents.icons.get(0).data);
        assertEquals(TV_INPUT, contents.icons.get(1).identity);
        assertArrayEquals(new byte[] {4, 5}, contents.icons.get(1).data);
    }

    @Test public void readAny_legacyTextBackup_isReturnedAsSettings() throws IOException {
        BackupArchive.Contents contents =
                BackupArchive.readAny(in(SETTINGS.getBytes(StandardCharsets.UTF_8)));

        assertNotNull(contents);
        assertEquals(SETTINGS, contents.settings);
        assertTrue(contents.icons.isEmpty());
    }

    @Test public void readAny_legacyTextOverLimit_isRefused() throws IOException {
        byte[] text = new byte[BackupArchive.MAX_SETTINGS_BYTES + 1];
        Arrays.fill(text, (byte) 'a');

        assertNull(BackupArchive.readAny(in(text)));
    }

    @Test public void readAny_zipWithoutSettings_isRefused() throws IOException {
        assertNull(BackupArchive.readAny(in(zip(
                BackupArchive.entryName("com.a"), new byte[] {1}))));
    }

    @Test public void readAny_duplicateSettings_isRefused() throws IOException {
        // ZipOutputStream refuses duplicate names, so write a twin and rename it.
        byte[] archive = rename(zip(
                BackupArchive.SETTINGS_ENTRY, utf8(SETTINGS),
                "settingz.txt", utf8(SETTINGS)), "settingz.txt", BackupArchive.SETTINGS_ENTRY);

        assertNull(BackupArchive.readAny(in(archive)));
    }

    @Test public void readAny_entriesThatAreNotIcons_areIgnored() throws IOException {
        BackupArchive.Contents contents = BackupArchive.readAny(in(zip(
                BackupArchive.SETTINGS_ENTRY, utf8(SETTINGS),
                "icons/../escape.webp", new byte[] {1},
                "icons/sub/dir.webp", new byte[] {2},
                "icons/.webp", new byte[] {3},
                "wallpaper.jpg", new byte[] {4})));

        assertNotNull(contents);
        assertEquals(SETTINGS, contents.settings);
        assertTrue(contents.icons.isEmpty());
    }

    @Test public void readAny_tooManyEntries_isRefused() throws IOException {
        BackupArchive.Limits limits = new BackupArchive.Limits(
                BackupArchive.MAX_SETTINGS_BYTES, BackupArchive.MAX_ICON_BYTES, 2, 1 << 20);

        assertNull(BackupArchive.readAny(in(zip(
                BackupArchive.SETTINGS_ENTRY, utf8(SETTINGS),
                BackupArchive.entryName("com.a"), new byte[] {1},
                BackupArchive.entryName("com.b"), new byte[] {2})), limits));
    }

    @Test public void readAny_oversizedIcon_isSkipped() throws IOException {
        BackupArchive.Limits limits = new BackupArchive.Limits(
                BackupArchive.MAX_SETTINGS_BYTES, 4, 16, 1 << 20);

        BackupArchive.Contents contents = BackupArchive.readAny(in(zip(
                BackupArchive.SETTINGS_ENTRY, utf8(SETTINGS),
                BackupArchive.entryName("com.a"), new byte[] {1, 2, 3, 4, 5},
                BackupArchive.entryName("com.b"), new byte[] {6, 7, 8, 9})), limits);

        assertNotNull(contents);
        assertEquals(1, contents.icons.size());
        assertEquals("com.b", contents.icons.get(0).identity);
    }

    @Test public void readAny_decompressedBytesOverLimit_isRefused() throws IOException {
        BackupArchive.Limits limits = new BackupArchive.Limits(
                BackupArchive.MAX_SETTINGS_BYTES, BackupArchive.MAX_ICON_BYTES, 16, 64 * 1024);
        byte[] archive = zip(
                BackupArchive.SETTINGS_ENTRY, utf8(SETTINGS),
                "padding.bin", new byte[1 << 20]);

        assertTrue("the zeros compress far below the limit", archive.length < 64 * 1024);
        assertNull(BackupArchive.readAny(in(archive), limits));
    }

    @Test public void readAny_corruptZip_isRefused() throws IOException {
        byte[] archive = zip(BackupArchive.SETTINGS_ENTRY, utf8(SETTINGS));
        for (int i = 4; i < archive.length; i++) archive[i] = (byte) 0xA5;

        assertNull(BackupArchive.readAny(in(archive)));
    }

    @Test public void entryName_roundTripsIdentitiesWithoutDotDotOrSlashes() {
        for (String identity : Arrays.asList(
                "com.example.app", TV_INPUT, "tvinput://oem/..Bar/HW1", "tvinput://oem/HW1.")) {
            String name = BackupArchive.entryName(identity);

            assertFalse(name, name.contains(".."));
            assertEquals(name, BackupArchive.ICON_PREFIX.length() - 1, name.lastIndexOf('/'));
            assertEquals(identity, BackupArchive.identityOf(name));
        }
        assertEquals("icons/com.example.app.webp", BackupArchive.entryName("com.example.app"));
    }

    private static InputStream in(byte[] data) {
        return new ByteArrayInputStream(data);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** A zip of (name, bytes) pairs, deflated. */
    private static byte[] zip(Object... namesAndBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int i = 0; i < namesAndBytes.length; i += 2) {
                zip.putNextEntry(new ZipEntry((String) namesAndBytes[i]));
                zip.write((byte[]) namesAndBytes[i + 1]);
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** Replace every occurrence of an entry name with another of the same length. */
    private static byte[] rename(byte[] archive, String from, String to) {
        byte[] a = utf8(from);
        byte[] b = utf8(to);
        assertEquals(a.length, b.length);
        int replaced = 0;
        for (int i = 0; i + a.length <= archive.length; i++) {
            if (Arrays.equals(Arrays.copyOfRange(archive, i, i + a.length), a)) {
                System.arraycopy(b, 0, archive, i, b.length);
                replaced++;
            }
        }
        assertTrue(replaced > 0);
        return archive;
    }
}
