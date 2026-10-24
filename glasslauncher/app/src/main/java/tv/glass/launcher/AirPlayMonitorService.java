package tv.glass.launcher;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import io.github.jqssun.airplay.service.AirPlayService;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Background host for Glass's always-on services, running in the small
 * {@code :airplay} process: the built-in AirPlay receiver
 * ({@link AirPlayService}) and the phone remote.
 *
 * <p>The receiver is part of Glass, so health is read directly from the
 * engine — server running and every mDNS record registered — instead of
 * probing the network for another app's advertisement. The engine re-registers
 * itself when the network changes; this service repairs anything else
 * (stopped server, refused advertisement) on each keep-alive tick.
 *
 * <p>TCL firmware freezes idle third-party processes about 200 s after their
 * last service start, and a frozen receiver cannot answer an iPhone. An
 * AlarmManager tick restarts this service every {@link #KEEPALIVE_MS}, which
 * thaws the process and renews that window; the tick also runs the health
 * repair.
 *
 * <p>State reaches the launcher through a private, timestamped file plus a
 * package-scoped "changed" ping; the launcher re-reads the file and treats a
 * stale one as unknown.
 */
public final class AirPlayMonitorService extends Service {

    /** Former standalone receiver. Its advertisement would duplicate ours. */
    static final String LEGACY_RECEIVER_PKG = "io.github.jqssun.airplay";
    static final String ACTION_CHANGED = "tv.glass.launcher.AIRPLAY_STATUS_CHANGED";
    /** The launcher is visible, so the screen is definitely in use. */
    static final String ACTION_IN_USE = "tv.glass.launcher.AIRPLAY_IN_USE";
    /** Started from BOOT_COMPLETED: in use, with the longer boot grace. */
    static final String ACTION_BOOT = "tv.glass.launcher.AIRPLAY_BOOT";
    /** Turn the receiver on or off ({@link #EXTRA_ENABLED}). */
    static final String ACTION_SET_ENABLED = "tv.glass.launcher.AIRPLAY_SET_ENABLED";
    /** Restart the receiver now (drops any active session). */
    static final String ACTION_RESTART = "tv.glass.launcher.AIRPLAY_RESTART";
    /** The phone remote was switched on/off or its pairing was replaced. */
    static final String ACTION_REMOTE_CHANGED = "tv.glass.launcher.REMOTE_CHANGED";
    /** Switch detailed AirPlay logging ({@link #EXTRA_ENABLED}). */
    static final String ACTION_SET_DEBUG = "tv.glass.launcher.AIRPLAY_SET_DEBUG";
    static final String EXTRA_ENABLED = "enabled";
    private static final String ACTION_TICK = "tv.glass.launcher.REMOTE_CHECK";
    private static final String STATUS_FILE = "airplay_status";
    private static final String DISABLED_FILE = "airplay_disabled";
    private static final String CHANNEL = "airplay_monitor";
    private static final String TAG = "GlassAirPlay";
    private static final int NOTIFICATION_ID = 0xA1A;

    private static final long BOOT_GRACE_MS = 60_000L;
    private static final long WAKE_GRACE_MS = 20_000L;
    /** Inside TCL's ~200 s freeze window. */
    private static final long KEEPALIVE_MS = 120_000L;
    private static final long FREEZE_WINDOW_MS = 190_000L;
    /** Re-check cadence while starting or failed. */
    private static final long RETRY_MS = 15_000L;
    /** Status older than this is shown as unknown (amber) by the launcher. */
    static final long STALE_MS = 10 * 60_000L;
    private static final long BANNER_MS = 8_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AirPlayStatus status = new AirPlayStatus();
    private final Runnable engineChanged = () -> check(false);
    private final Runnable retry = this::repairAndCheck;
    private BroadcastReceiver screenReceiver;
    private boolean dreaming;
    private String publishedRecord;
    private View banner;
    private GlassRemoteServer phoneRemote;
    /** When the last keep-alive tick ran, to spot ticks that came too late. */
    private long lastTickAt;

    // ── Entry points used by the launcher ─────────────────────────────────

    static void start(Context ctx, String action) {
        send(ctx, new Intent(ctx, AirPlayMonitorService.class).setAction(action));
    }

    static void setDebug(Context ctx, boolean enabled) {
        send(ctx, new Intent(ctx, AirPlayMonitorService.class).setAction(ACTION_SET_DEBUG)
                .putExtra(EXTRA_ENABLED, enabled));
    }

    static void setEnabled(Context ctx, boolean enabled) {
        send(ctx, new Intent(ctx, AirPlayMonitorService.class).setAction(ACTION_SET_ENABLED)
                .putExtra(EXTRA_ENABLED, enabled));
    }

    private static void send(Context ctx, Intent i) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (IllegalStateException | SecurityException e) {
            Log.w(TAG, "monitor start refused", e);
        }
    }

    static boolean enabled(Context ctx) {
        return !new File(ctx.getFilesDir(), DISABLED_FILE).exists();
    }

    static boolean legacyReceiverInstalled(Context ctx) {
        try {
            return ctx.getPackageManager().getApplicationInfo(LEGACY_RECEIVER_PKG, 0).enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Snapshot published by the monitor process. */
    static final class Snapshot {
        final AirPlayStatus.State state;
        /** "mirroring", "video", "audio", "connecting" or "idle". */
        final String session;
        /** Name iPhones see, or empty before the first start. */
        final String name;
        /** Decoder and counters of the last mirroring session, last error. */
        final String diagnostics;
        /** Detailed native logging is on. */
        final boolean debug;
        Snapshot(AirPlayStatus.State state, String session, String name) { this(state, session, name, "", false); }
        Snapshot(AirPlayStatus.State state, String session, String name, String diagnostics, boolean debug) {
            this.state = state; this.session = session; this.name = name;
            this.diagnostics = diagnostics; this.debug = debug;
        }
        boolean inSession() { return !"idle".equals(session); }
    }

    /** Last published snapshot; OFF when disabled, STARTING when the record
     *  is missing or older than {@link #STALE_MS}. */
    static Snapshot read(Context ctx) {
        if (!enabled(ctx)) return new Snapshot(AirPlayStatus.State.OFF, "idle", "");
        File f = new File(ctx.getFilesDir(), STATUS_FILE);
        try {
            String[] parts = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).split("\n", -1);
            long at = parts.length > 1 ? Long.parseLong(parts[1].trim()) : 0L;
            String session = parts.length > 2 ? parts[2] : "idle";
            String name = parts.length > 3 ? parts[3] : "";
            String diagnostics = parts.length > 4 ? parts[4] : "";
            boolean debug = parts.length > 5 && "1".equals(parts[5]);
            boolean stale = Math.abs(System.currentTimeMillis() - at) > STALE_MS;
            AirPlayStatus.State s = stale ? AirPlayStatus.State.STARTING : AirPlayStatus.parse(parts[0]);
            // A stale file must not keep claiming "Mirroring now".
            return new Snapshot(s, stale ? "idle" : session, name, diagnostics, debug);
        } catch (Exception e) {
            return new Snapshot(AirPlayStatus.State.STARTING, "idle", "");
        }
    }

    static AirPlayStatus.State readState(Context ctx) { return read(ctx).state; }

    // ── Service lifecycle ─────────────────────────────────────────────────

    @Override public void onCreate() {
        super.onCreate();
        goForeground();
        registerScreenReceiver();
        AirPlayService.addListener(engineChanged);
        syncPhoneRemote();
        if (isInteractive()) status.onInUse(SystemClock.elapsedRealtime(), BOOT_GRACE_MS);
        else status.onNotInUse();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        scheduleKeepAlive();
        String action = intent != null ? intent.getAction() : null;
        long now = SystemClock.elapsedRealtime();
        if (ACTION_TICK.equals(action)) {
            // TCL freezes us ~200 s after the last service start; a tick later
            // than that means the receiver may have been unreachable.
            if (lastTickAt > 0 && isInteractive() && now - lastTickAt > FREEZE_WINDOW_MS)
                Log.w(TAG, "keep-alive tick late: " + (now - lastTickAt) / 1000 + " s since the last one");
            lastTickAt = now;
        }
        if (ACTION_SET_ENABLED.equals(action)) {
            setEnabledInternal(intent.getBooleanExtra(EXTRA_ENABLED, true));
        } else if (ACTION_RESTART.equals(action)) {
            AirPlayService svc = AirPlayService.getInstance();
            if (svc != null) svc.disconnectSenders();
            status.onInUse(now, WAKE_GRACE_MS);
        } else if (ACTION_BOOT.equals(action)) {
            if (isInteractive()) status.onInUse(now, BOOT_GRACE_MS);
        } else if (ACTION_SET_DEBUG.equals(action)) {
            AirPlayService svc = AirPlayService.getInstance();
            if (svc != null) svc.setDebugLogging(intent.getBooleanExtra(EXTRA_ENABLED, false));
        } else if (ACTION_REMOTE_CHANGED.equals(action)) {
            syncPhoneRemote();
        } else if (ACTION_IN_USE.equals(action)) {
            if (!status.inUse()) status.onInUse(now, WAKE_GRACE_MS);
        }
        repairAndCheck();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        if (phoneRemote != null) phoneRemote.close();
        AirPlayService.removeListener(engineChanged);
        handler.removeCallbacksAndMessages(null);
        if (screenReceiver != null) {
            try { unregisterReceiver(screenReceiver); } catch (Exception ignored) { }
            screenReceiver = null;
        }
        hideBanner();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    /** Run the phone remote's web server only while it is switched on. */
    private void syncPhoneRemote() {
        boolean want = GlassRemoteAccess.enabled(this);
        if (!want && phoneRemote != null) { phoneRemote.close(); phoneRemote = null; Log.i(TAG, "Phone remote off"); }
        if (want && phoneRemote == null) {
            try {
                phoneRemote = new GlassRemoteServer(this, () -> handler.post(() ->
                        Toast.makeText(this, R.string.remote_lockout, Toast.LENGTH_LONG).show()));
                phoneRemote.start();
            } catch (Exception e) {
                Log.w(TAG, "Phone remote could not start", e);
                if (phoneRemote != null) phoneRemote.close();
                phoneRemote = null;
            }
        }
    }

    private void setEnabledInternal(boolean on) {
        File flag = new File(getFilesDir(), DISABLED_FILE);
        if (on) {
            if (flag.exists() && !flag.delete()) Log.w(TAG, "could not clear disabled flag");
            status.onInUse(SystemClock.elapsedRealtime(), WAKE_GRACE_MS);
        } else {
            try { new FileOutputStream(flag).close(); } catch (Exception e) { Log.w(TAG, "disable flag", e); }
            AirPlayService svc = AirPlayService.getInstance();
            if (svc != null) svc.stopServer();
            hideBanner();
        }
    }

    /** Start or heal the engine, then publish. Safe to call repeatedly. */
    private void repairAndCheck() {
        handler.removeCallbacks(retry);
        if (enabled(this)) {
            AirPlayService svc = AirPlayService.getInstance();
            if (svc == null) {
                Intent i = new Intent(this, AirPlayService.class).setAction(AirPlayService.ACTION_START_SERVER);
                try { startForegroundService(i); } catch (RuntimeException e) { Log.w(TAG, "engine start refused", e); }
            } else {
                svc.ensureHealthy();
            }
        }
        check(true);
    }

    /** Engine events only ever confirm health; misses are counted on timed
     *  checks, so a deliberate restart (network change, Stop mirroring) is
     *  never reported as a failure while it re-registers. */
    private void check(boolean timed) {
        AirPlayService svc = AirPlayService.getInstance();
        boolean on = enabled(this);
        boolean advertised = svc != null && svc.isAdvertised();
        boolean alert = (timed || advertised || !on)
                && status.onCheck(SystemClock.elapsedRealtime(), on, advertised);
        publish(svc);
        if (alert && status.inUse()) showBanner();
        if (status.state() == AirPlayStatus.State.READY) hideBanner();
        if (on && status.state() != AirPlayStatus.State.READY) handler.postDelayed(retry, RETRY_MS);
    }

    // ── Keep-alive ────────────────────────────────────────────────────────

    private PendingIntent tick() {
        return PendingIntent.getForegroundService(this, 48633, new Intent(this, AirPlayMonitorService.class)
                .setAction(ACTION_TICK), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Non-wakeup: a sleeping TV defers the tick, and nothing needs the
     *  receiver until the screen is back. Exact where allowed: an inexact
     *  alarm may be batched past TCL's ~200 s freeze window. */
    private void scheduleKeepAlive() {
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (am == null) return;
        long at = SystemClock.elapsedRealtime() + KEEPALIVE_MS;
        boolean exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms();
        try {
            if (exact) am.setExact(AlarmManager.ELAPSED_REALTIME, at, tick());
            else am.set(AlarmManager.ELAPSED_REALTIME, at, tick());
        } catch (SecurityException e) {
            am.set(AlarmManager.ELAPSED_REALTIME, at, tick());
        }
    }

    private boolean isInteractive() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isInteractive();
    }

    private void goForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    getString(R.string.airplay_channel), NotificationManager.IMPORTANCE_MIN);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_airplay)
                .setContentTitle(getString(R.string.airplay_channel))
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
        startForeground(NOTIFICATION_ID, n);
    }

    // ── Screen / screensaver tracking ─────────────────────────────────────

    private void registerScreenReceiver() {
        screenReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent it) {
                String a = it.getAction();
                if (a == null) return;
                long now = SystemClock.elapsedRealtime();
                switch (a) {
                    case Intent.ACTION_SCREEN_OFF:
                        dreaming = false; status.onNotInUse(); hideBanner(); break;
                    case Intent.ACTION_DREAMING_STARTED:
                        dreaming = true; status.onNotInUse(); hideBanner(); break;
                    case Intent.ACTION_DREAMING_STOPPED:
                        dreaming = false;
                        if (isInteractive()) { status.onInUse(now, WAKE_GRACE_MS); repairAndCheck(); }
                        break;
                    case Intent.ACTION_SCREEN_ON:
                        if (!dreaming) { status.onInUse(now, WAKE_GRACE_MS); repairAndCheck(); }
                        break;
                    default:
                        break;
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_DREAMING_STARTED);
        f.addAction(Intent.ACTION_DREAMING_STOPPED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            registerReceiver(screenReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else
            registerReceiver(screenReceiver, f);
    }

    // ── Publishing ────────────────────────────────────────────────────────

    /** Persist state, session and name with a timestamp; ping the launcher
     *  when anything but the timestamp changed. */
    private void publish(AirPlayService svc) {
        String session = svc != null ? svc.sessionKind() : "idle";
        String name = svc != null ? svc.advertisedName() : "";
        String diagnostics = svc != null ? svc.diagnostics() : "";
        boolean debug = svc != null && svc.debugLogging();
        String record = status.state().name() + "\n%d\n" + session + "\n" + name.replace('\n', ' ')
                + "\n" + diagnostics.replace('\n', ' ') + "\n" + (debug ? "1" : "0");
        File dst = new File(getFilesDir(), STATUS_FILE);
        File tmp = new File(getFilesDir(), STATUS_FILE + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(String.format(java.util.Locale.ROOT, record, System.currentTimeMillis())
                    .getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Exception e) {
            Log.w(TAG, "status write failed", e);
            return;
        }
        if (!tmp.renameTo(dst)) { Log.w(TAG, "status rename failed"); return; }
        if (record.equals(publishedRecord)) return;
        publishedRecord = record;
        Log.i(TAG, "state " + status.state() + " session " + session);
        sendBroadcast(new Intent(ACTION_CHANGED).setPackage(getPackageName()));
    }

    // ── Failure banner ────────────────────────────────────────────────────

    /** Brief, non-focusable glass banner over whatever app is open. It never
     *  takes remote focus or touches. Falls back to a toast without the
     *  overlay permission. */
    private void showBanner() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.airplay_banner_title, Toast.LENGTH_LONG).show();
            return;
        }
        hideBanner();
        NativeTheme.init(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(NativeTheme.px(this, 22), NativeTheme.px(this, 14), NativeTheme.px(this, 22), NativeTheme.px(this, 14));
        box.setBackground(NativeTheme.glass(this, 22, true));
        TextView title = new TextView(this);
        title.setText(R.string.airplay_banner_title);
        title.setTextColor(0xFFFFFFFF);
        title.setTypeface(NativeTheme.medium());
        title.setTextSize(TypedValue.COMPLEX_UNIT_PX, NativeTheme.px(this, 16));
        box.addView(title);
        TextView body = new TextView(this);
        body.setText(R.string.airplay_banner_body);
        body.setTextColor(0xCCFFFFFF);
        body.setTypeface(NativeTheme.regular());
        body.setTextSize(TypedValue.COMPLEX_UNIT_PX, NativeTheme.px(this, 13));
        box.addView(body);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = NativeTheme.px(this, 32);
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        try {
            wm.addView(box, lp);
            banner = box;
            handler.postDelayed(this::hideBanner, BANNER_MS);
        } catch (RuntimeException e) {
            Log.w(TAG, "banner refused", e);
        }
    }

    private void hideBanner() {
        View b = banner;
        banner = null;
        if (b == null) return;
        try {
            ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).removeView(b);
        } catch (RuntimeException ignored) { }
    }
}
