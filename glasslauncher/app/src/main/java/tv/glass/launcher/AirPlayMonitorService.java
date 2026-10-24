package tv.glass.launcher;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
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

import java.io.File;
import java.io.FileOutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Watches the background AirPlay receiver ({@link #RECEIVER_PKG}) while the TV
 * is in use and drives the toolbar pill and failure banner.
 *
 * <p>"Ready" means this TV's own {@code _airplay._tcp} service is visible on
 * the local network — the same advertisement an iPhone's Screen Mirroring
 * list reads — confirmed by resolving it to one of this device's addresses.
 * The probe never opens a connection to the receiver: any TCP connection
 * counts as an AirPlay client for it and brings its player to the front.
 *
 * <p>Runs in its own small {@code :airplay} process as a minimum-priority
 * foreground service so it survives while other apps are open without
 * pinning the launcher's wallpaper / icon memory. Probes pause while the
 * screen is off or the screensaver runs; nothing is checked or shown then.
 *
 * <p>TCL firmware freezes idle third-party processes (foreground services
 * included) about 200 s after their last service start. Two consequences:
 * <ul>
 *   <li>Checks are scheduled with AlarmManager re-starting this service rather
 *       than an in-process timer, because a frozen process's timers never
 *       fire. Each alarm-driven start thaws the process and renews that
 *       window; the READY interval stays inside it.</li>
 *   <li>A frozen receiver stops answering connections even though Android's
 *       mDNS daemon keeps advertising it. Every check therefore first sends
 *       the receiver's own exported boot receiver an (unprotected)
 *       {@code QUICKBOOT_POWERON}; it asks the receiver to start its server —
 *       a no-op when already running, a restart when it died — and counts as
 *       a service start, keeping it thawed while the TV is in use.</li>
 * </ul>
 *
 * <p>State reaches the launcher UI through a private, timestamped file plus a
 * package-scoped "changed" ping; the UI re-reads the file rather than
 * trusting broadcast extras, and treats a stale file as unknown.
 */
public final class AirPlayMonitorService extends Service {

    static final String RECEIVER_PKG = "io.github.jqssun.airplay";
    static final String ACTION_CHANGED = "tv.glass.launcher.AIRPLAY_STATUS_CHANGED";
    /** Alarm tick: run a check if the screen is in use. */
    static final String ACTION_CHECK = "tv.glass.launcher.AIRPLAY_CHECK";
    /** The launcher is visible, so the screen is definitely in use. */
    static final String ACTION_IN_USE = "tv.glass.launcher.AIRPLAY_IN_USE";
    /** Started from BOOT_COMPLETED: in use, with the longer boot grace. */
    static final String ACTION_BOOT = "tv.glass.launcher.AIRPLAY_BOOT";
    private static final String RECEIVER_BOOT_COMPONENT = ".BootReceiver";
    private static final String QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON";
    private static final String STATUS_FILE = "airplay_status";
    private static final String CHANNEL = "airplay_monitor";
    private static final String TAG = "GlassAirPlay";
    private static final int NOTIFICATION_ID = 0xA1A;

    private static final long BOOT_GRACE_MS = 120_000L;
    private static final long WAKE_GRACE_MS = 45_000L;
    private static final long READY_INTERVAL_MS = 150_000L;
    private static final long PENDING_INTERVAL_MS = 30_000L;
    private static final long FAILED_INTERVAL_MS = 60_000L;
    private static final long KEEPALIVE_SETTLE_MS = 3_000L;
    private static final long DISCOVER_MS = 5_000L;
    private static final long RESOLVE_TIMEOUT_MS = 4_000L;
    private static final int MAX_RESOLVES = 10;
    /** Consecutive lost resolve callbacks before recycling the process, which
     *  releases any NSD requests the firmware never answered. */
    private static final int MAX_LOST_RESOLVES = 8;
    private static final long RECYCLE_RESTART_MS = 3_000L;
    /** Status older than this is shown as unknown (amber) by the launcher. */
    static final long STALE_MS = 10 * 60_000L;
    private static final long BANNER_MS = 8_000L;
    private static final String SERVICE_TYPE = "_airplay._tcp";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AirPlayStatus status = new AirPlayStatus();
    private NsdManager nsd;
    private BroadcastReceiver screenReceiver;
    private boolean initialized;
    private boolean dreaming;
    private boolean probing;
    private int probeGen;
    private int lostResolves;
    private AirPlayStatus.State published;
    private View banner;

    private GlassRemoteServer phoneRemote;
    private final Runnable checkTick = this::runCheck;

    // ── Entry points used by the launcher ─────────────────────────────────

    /** Start (or nudge) the monitor with {@code action} when the receiver app
     *  is present. */
    static void start(Context ctx, String action) {
        Intent i = new Intent(ctx, AirPlayMonitorService.class).setAction(action);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (IllegalStateException | SecurityException e) {
            Log.w(TAG, "monitor start refused", e);
        }
    }

    static boolean receiverInstalled(Context ctx) {
        try {
            ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(RECEIVER_PKG, 0);
            return ai.enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Last published state; OFF when the receiver is absent, STARTING when
     *  the record is missing or older than {@link #STALE_MS}. */
    static AirPlayStatus.State readState(Context ctx) {
        if (!receiverInstalled(ctx)) return AirPlayStatus.State.OFF;
        File f = new File(ctx.getFilesDir(), STATUS_FILE);
        try {
            String[] parts = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)
                    .split("\n");
            long at = parts.length > 1 ? Long.parseLong(parts[1].trim()) : 0L;
            if (Math.abs(System.currentTimeMillis() - at) > STALE_MS) return AirPlayStatus.State.STARTING;
            return AirPlayStatus.parse(parts[0]);
        } catch (Exception e) {
            return AirPlayStatus.State.STARTING;
        }
    }

    // ── Service lifecycle ─────────────────────────────────────────────────

    @Override public void onCreate() {
        super.onCreate();
        nsd = (NsdManager) getSystemService(Context.NSD_SERVICE);
        goForeground();
        registerScreenReceiver();
        try { phoneRemote=new GlassRemoteServer(this);phoneRemote.start(); }
        catch(Exception e) { Log.w(TAG,"Phone remote could not start",e); }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        scheduleRemoteKeepAlive();
        String action = intent != null ? intent.getAction() : null;
        long now = SystemClock.elapsedRealtime();
        if (ACTION_BOOT.equals(action)) {
            initialized = true;
            if (isInteractive()) startInUse(now, BOOT_GRACE_MS);
            else status.onNotInUse();
        } else if (ACTION_IN_USE.equals(action)) {
            initialized = true;
            if (!status.inUse()) startInUse(now, WAKE_GRACE_MS);
            else schedule(0);
        } else if (ACTION_CHECK.equals(action)) {
            // Alarms are armed only while in use, so a tick into a fresh
            // process (killed or recycled while in use) resumes monitoring.
            if (!initialized) {
                initialized = true;
                if (isInteractive()) startInUse(now, WAKE_GRACE_MS);
                else status.onNotInUse();
            } else if (status.inUse()) {
                schedule(0);
            }
        } else if (!initialized) {
            // Sticky restart: the screen may be in a screensaver, which has no
            // public query, so stay paused until a screen event or the launcher
            // says the TV is in use.
            initialized = true;
            status.onNotInUse();
        }
        return START_STICKY;
    }

    private PendingIntent remoteTick() {
        return PendingIntent.getForegroundService(this,48633,new Intent(this,AirPlayMonitorService.class)
            .setAction("tv.glass.launcher.REMOTE_CHECK"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
    }
    private void scheduleRemoteKeepAlive() {
        AlarmManager am=(AlarmManager)getSystemService(ALARM_SERVICE);
        if(am!=null)am.set(AlarmManager.ELAPSED_REALTIME,SystemClock.elapsedRealtime()+120000,remoteTick());
    }

    private boolean isInteractive() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm == null || pm.isInteractive();
    }

    private PendingIntent checkIntent() {
        Intent i = new Intent(this, AirPlayMonitorService.class).setAction(ACTION_CHECK);
        return PendingIntent.getForegroundService(this, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void cancelAlarm() {
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(checkIntent());
    }

    @Override public void onDestroy() {
        if(phoneRemote!=null)phoneRemote.close();
        AlarmManager remoteAlarm=(AlarmManager)getSystemService(ALARM_SERVICE);
        if(remoteAlarm!=null)remoteAlarm.cancel(remoteTick());
        handler.removeCallbacksAndMessages(null);
        cancelAlarm();
        probeGen++;
        if (screenReceiver != null) {
            try { unregisterReceiver(screenReceiver); } catch (Exception ignored) { }
            screenReceiver = null;
        }
        hideBanner();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

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
                initialized = true;
                switch (a) {
                    case Intent.ACTION_SCREEN_OFF:
                        dreaming = false;
                        pause();
                        break;
                    case Intent.ACTION_DREAMING_STARTED:
                        dreaming = true;
                        pause();
                        break;
                    case Intent.ACTION_DREAMING_STOPPED:
                        dreaming = false;
                        // Also sent on the way into standby; resume only if
                        // the screen is actually on.
                        if (isInteractive()) startInUse(SystemClock.elapsedRealtime(), WAKE_GRACE_MS);
                        break;
                    case Intent.ACTION_SCREEN_ON:
                        if (!dreaming) startInUse(SystemClock.elapsedRealtime(), WAKE_GRACE_MS);
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

    private void pause() {
        status.onNotInUse();
        handler.removeCallbacks(checkTick);
        cancelAlarm();
        probeGen++;
        probing = false;
        hideBanner();
    }

    private void startInUse(long now, long graceMs) {
        status.onInUse(now, graceMs);
        publish();
        schedule(0);
    }

    /** Run a check now, or arm the alarm that restarts this service for the
     *  next one (non-wakeup: a sleeping TV defers it, which is fine because
     *  checks only matter while the screen is in use). */
    private void schedule(long delayMs) {
        handler.removeCallbacks(checkTick);
        cancelAlarm();
        if (delayMs <= 0) {
            handler.post(checkTick);
            return;
        }
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + delayMs, checkIntent());
        } else {
            handler.postDelayed(checkTick, delayMs);
        }
    }

    // ── Probe ─────────────────────────────────────────────────────────────

    private void runCheck() {
        if (probing || !status.inUse()) return;
        if (!receiverInstalled(this)) {
            finishCheck(false, false);
            return;
        }
        probing = true;
        final int gen = ++probeGen;
        keepReceiverAwake();
        handler.postDelayed(() -> { if (gen == probeGen) new Probe(gen).start(); }, KEEPALIVE_SETTLE_MS);
    }

    /** Thaw the receiver (and restart its server if it died) via its exported
     *  boot receiver. Harmless when it is already running. */
    private void keepReceiverAwake() {
        Intent i = new Intent(QUICKBOOT).setComponent(
                new ComponentName(RECEIVER_PKG, RECEIVER_PKG + RECEIVER_BOOT_COMPONENT));
        try {
            sendBroadcast(i);
        } catch (RuntimeException e) {
            Log.w(TAG, "receiver keep-alive refused", e);
        }
    }

    /** Record a completed check. A probe that could not run (discovery itself
     *  failed) is inconclusive: it neither confirms nor counts as a miss. */
    private void finishInconclusive() {
        probing = false;
        if (!status.inUse()) return;
        refreshTimestamp();
        schedule(PENDING_INTERVAL_MS);
    }

    private void finishCheck(boolean installed, boolean advertised) {
        probing = false;
        if (!status.inUse()) return;
        boolean alert = status.onCheck(SystemClock.elapsedRealtime(), installed, advertised);
        publish();
        if (alert) showBanner();
        if (status.state() == AirPlayStatus.State.READY) hideBanner();
        if (status.state() == AirPlayStatus.State.OFF) return; // The phone remote still runs.
        // A miss while still READY rechecks soon so a stopped receiver is
        // reported within about a minute rather than a full READY interval.
        long next;
        if (status.state() == AirPlayStatus.State.READY)
            next = advertised ? READY_INTERVAL_MS : PENDING_INTERVAL_MS;
        else if (status.state() == AirPlayStatus.State.FAILED)
            next = FAILED_INTERVAL_MS;
        else
            next = PENDING_INTERVAL_MS;
        schedule(next);
    }

    /** NSD on this firmware sometimes never answers a resolve. Those requests
     *  stay open inside the system service; after a run of them, restart this
     *  small process (an alarm brings it straight back) so they are released. */
    private void onResolveLost() {
        if (++lostResolves < MAX_LOST_RESOLVES) return;
        Log.w(TAG, "recycling monitor after " + lostResolves + " lost resolves");
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + RECYCLE_RESTART_MS,
                checkIntent());
        hideBanner();
        Process.killProcess(Process.myPid());
    }

    /** One discover-then-resolve pass. Every callback is re-posted to the
     *  main handler and dropped if a newer probe (or a pause) superseded it. */
    private final class Probe {
        private final int gen;
        private final ArrayDeque<NsdServiceInfo> found = new ArrayDeque<>();
        private final Set<String> seen = new HashSet<>();
        private NsdManager.DiscoveryListener listener;
        private boolean done;
        private int resolves;

        Probe(int gen) { this.gen = gen; }

        private boolean stale() { return gen != probeGen || done; }

        void start() {
            listener = new NsdManager.DiscoveryListener() {
                @Override public void onDiscoveryStarted(String t) { }
                @Override public void onServiceFound(NsdServiceInfo info) {
                    handler.post(() -> {
                        if (stale() || info == null || info.getServiceName() == null) return;
                        if (seen.add(info.getServiceName())) found.add(info);
                    });
                }
                @Override public void onServiceLost(NsdServiceInfo info) { }
                @Override public void onDiscoveryStopped(String t) { }
                @Override public void onStartDiscoveryFailed(String t, int code) {
                    handler.post(() -> {
                        Log.w(TAG, "discovery failed: " + code);
                        listener = null;
                        inconclusive();
                    });
                }
                @Override public void onStopDiscoveryFailed(String t, int code) { }
            };
            try {
                nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener);
            } catch (RuntimeException e) {
                Log.w(TAG, "discovery threw", e);
                listener = null;
                inconclusive();
                return;
            }
            handler.postDelayed(() -> {
                stopDiscovery();
                if (!stale()) resolveNext();
            }, DISCOVER_MS);
        }

        private void stopDiscovery() {
            NsdManager.DiscoveryListener l = listener;
            listener = null;
            if (l == null) return;
            try { nsd.stopServiceDiscovery(l); } catch (RuntimeException ignored) { }
        }

        private void resolveNext() {
            if (stale()) return;
            NsdServiceInfo next = found.poll();
            if (next == null || resolves >= MAX_RESOLVES) { complete(false); return; }
            resolves++;
            final boolean[] answered = {false};
            final Runnable timeout = () -> {
                if (answered[0] || stale()) return;
                answered[0] = true;
                onResolveLost();
                resolveNext();
            };
            try {
                nsd.resolveService(next, new NsdManager.ResolveListener() {
                    @Override public void onResolveFailed(NsdServiceInfo i, int code) {
                        handler.post(() -> {
                            if (answered[0] || stale()) return;
                            answered[0] = true;
                            handler.removeCallbacks(timeout);
                            resolveNext();
                        });
                    }
                    @Override public void onServiceResolved(NsdServiceInfo i) {
                        handler.post(() -> {
                            if (answered[0] || stale()) return;
                            answered[0] = true;
                            lostResolves = 0;
                            handler.removeCallbacks(timeout);
                            if (isLocal(i.getHost())) complete(true);
                            else resolveNext();
                        });
                    }
                });
            } catch (RuntimeException e) {
                answered[0] = true;
                resolveNext();
                return;
            }
            handler.postDelayed(timeout, RESOLVE_TIMEOUT_MS);
        }

        private void complete(boolean advertised) {
            if (stale()) return;
            done = true;
            stopDiscovery();
            finishCheck(true, advertised);
        }

        private void inconclusive() {
            if (stale()) return;
            done = true;
            stopDiscovery();
            finishInconclusive();
        }
    }

    /** True when {@code host} is one of this device's addresses. Compares raw
     *  address bytes so an IPv6 scope id cannot make our own address look
     *  foreign. */
    private static boolean isLocal(InetAddress host) {
        if (host == null) return false;
        if (host.isLoopbackAddress()) return true;
        byte[] want = host.getAddress();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (Arrays.equals(a.getAddress(), want)) return true;
                }
            }
        } catch (Exception ignored) { }
        return false;
    }

    // ── Publishing ────────────────────────────────────────────────────────

    /** Persist the state with a timestamp; ping the launcher on a change. Only
     *  while in use, so a restart during standby cannot clobber the record. */
    private void publish() {
        if (!status.inUse()) return;
        AirPlayStatus.State s = status.state();
        if (!writeStatus(s)) return;
        if (s == published) return;
        published = s;
        Log.i(TAG, "state " + s);
        sendBroadcast(new Intent(ACTION_CHANGED).setPackage(getPackageName()));
    }

    private void refreshTimestamp() {
        if (published != null) writeStatus(published);
    }

    private boolean writeStatus(AirPlayStatus.State s) {
        File dst = new File(getFilesDir(), STATUS_FILE);
        File tmp = new File(getFilesDir(), STATUS_FILE + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write((s.name() + "\n" + System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (Exception e) {
            Log.w(TAG, "status write failed", e);
            return false;
        }
        if (!tmp.renameTo(dst)) {
            Log.w(TAG, "status rename failed");
            return false;
        }
        return true;
    }

    // ── Failure banner ────────────────────────────────────────────────────

    /** Brief, non-focusable glass banner over whatever app is open. It never
     *  takes remote focus or touches, so it cannot interrupt playback
     *  controls; Retry lives on the launcher's AirPlay pill. Falls back to a
     *  toast if the overlay permission was not granted. */
    private void showBanner() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.airplay_banner_title, Toast.LENGTH_LONG).show();
            return;
        }
        hideBanner();
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int padH = Math.round(20 * d), padV = Math.round(12 * d);
        box.setPadding(padH, padV, padH, padV);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE61C1C1E);
        bg.setCornerRadius(16 * d);
        bg.setStroke(Math.max(1, Math.round(d)), 0x33FFFFFF);
        box.setBackground(bg);

        TextView title = new TextView(this);
        title.setText(R.string.airplay_banner_title);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        box.addView(title);

        TextView body = new TextView(this);
        body.setText(R.string.airplay_banner_body);
        body.setTextColor(0xCCFFFFFF);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        box.addView(body);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = Math.round(32 * d);
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        try {
            wm.addView(box, lp);
            banner = box;
            handler.postDelayed(this::hideBanner, BANNER_MS);
        } catch (RuntimeException e) {
            Log.w(TAG, "banner refused", e);
            Toast.makeText(this, R.string.airplay_banner_title, Toast.LENGTH_LONG).show();
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
