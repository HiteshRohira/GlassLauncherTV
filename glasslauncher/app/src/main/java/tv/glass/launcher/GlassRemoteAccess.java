package tv.glass.launcher;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/** Private pairing material shared by the launcher UI and monitor process,
 *  stored as a {@link PairingBook} under a cross-process file lock. */
final class GlassRemoteAccess {
    private static final String FILE = "phone-remote-access.json";
    private static final String DISABLED = "phone-remote-disabled";
    interface Change<T> { T apply(PairingBook book) throws Exception; }

    static final PairingBook.Random RANDOM = new PairingBook.Random() {
        private final SecureRandom random = new SecureRandom();
        @Override public String token() {
            byte[] bytes = new byte[32]; random.nextBytes(bytes);
            return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        }
        @Override public String pin() { return String.format(java.util.Locale.US, "%06d", random.nextInt(1000000)); }
    };

    /** Current pairing state (created on first use). */
    static PairingBook load(Context context) throws Exception { return update(context, null); }

    /** Read, apply {@code change} and save, atomically across processes. */
    static synchronized <T> T update(Context context, Change<T> change) throws Exception {
        try (java.io.RandomAccessFile lockFile = new java.io.RandomAccessFile(new File(context.getNoBackupFilesDir(), "phone-remote-access.lock"), "rw");
             java.nio.channels.FileLock lock = lockFile.getChannel().lock()) {
            AtomicFile file = new AtomicFile(new File(context.getNoBackupFilesDir(), FILE));
            PairingBook book;
            boolean dirty = false;
            try { book = parse(new String(file.readFully(), StandardCharsets.UTF_8)); }
            catch (java.io.FileNotFoundException e) { book = new PairingBook(RANDOM.pin()); dirty = true; }
            if (change == null) {
                if (dirty) write(file, book);
                @SuppressWarnings("unchecked") T same = (T) book;
                return same;
            }
            T result = change.apply(book);
            write(file, book);
            return result;
        }
    }

    /** "Forget paired phones": new code, every phone and install link revoked. */
    static void forgetAll(Context context) throws Exception { update(context, book -> { book.forgetAll(RANDOM); return null; }); }

    /** Changes whenever the pairing material is rewritten. */
    static long version(Context context) { return new File(context.getNoBackupFilesDir(), FILE).lastModified(); }
    static boolean enabled(Context context) { return !new File(context.getNoBackupFilesDir(), DISABLED).exists(); }
    static void setEnabled(Context context, boolean on) throws IOException {
        File flag = new File(context.getNoBackupFilesDir(), DISABLED);
        if (on) { if (flag.exists() && !flag.delete()) throw new IOException("Could not turn the phone remote on."); }
        else new FileOutputStream(flag).close();
    }

    private static PairingBook parse(String text) throws Exception {
        JSONObject json = new JSONObject(text);
        String pin = json.getString("pin");
        if (!pin.matches("[0-9]{6}")) throw new IOException("Invalid pairing material");
        PairingBook book = new PairingBook(pin);
        // Up to 1.1.6 every phone shared one token: keep it as a device so
        // phones paired before this version stay connected.
        String legacy = json.optString("token", "");
        if (legacy.length() == 43) book.devices.add(new PairingBook.Device(legacy, 0));
        JSONArray devices = json.optJSONArray("devices");
        if (devices != null) for (int i = 0; i < devices.length(); i++) {
            JSONObject d = devices.getJSONObject(i);
            book.devices.add(new PairingBook.Device(d.getString("token"), d.optLong("since")));
        }
        JSONArray installs = json.optJSONArray("installs");
        if (installs != null) for (int i = 0; i < installs.length(); i++) {
            JSONObject d = installs.getJSONObject(i);
            book.installs.add(new PairingBook.Install(d.getString("token"), d.getLong("expires")));
        }
        return book;
    }

    private static void write(AtomicFile file, PairingBook book) throws Exception {
        JSONArray devices = new JSONArray();
        for (PairingBook.Device d : book.devices) devices.put(new JSONObject().put("token", d.token).put("since", d.since));
        JSONArray installs = new JSONArray();
        for (PairingBook.Install i : book.installs) installs.put(new JSONObject().put("token", i.token).put("expires", i.expires));
        byte[] bytes = new JSONObject().put("pin", book.pin).put("devices", devices).put("installs", installs)
                .toString().getBytes(StandardCharsets.UTF_8);
        FileOutputStream out = file.startWrite();
        try { out.write(bytes); file.finishWrite(out); } catch (Exception failure) { file.failWrite(out); throw failure; }
    }

    private GlassRemoteAccess() { }
}
