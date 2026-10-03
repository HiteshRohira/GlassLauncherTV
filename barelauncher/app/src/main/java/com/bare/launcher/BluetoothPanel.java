package com.bare.launcher;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Dropdown under the toolbar Bluetooth pill: paired devices with live
 *  connection state, a per-device view (audio connect/disconnect and the
 *  system device page), an in-panel scan-and-pair flow on Android 12+, and a
 *  way out to system settings. Built lazily on first open and driven by the
 *  activity's key dispatch while visible. */
@SuppressLint("MissingPermission")
final class BluetoothPanel {

    interface Host {
        FrameLayout root();
        View anchor();
        Interpolator menuIn();
        Interpolator menuOut();
        boolean isLetThroughKey(int keyCode);
        void onPanelShown();
        void onPanelHidden();
        void openBtSettings();
        void openBtPairing();
        void openSystemSettings();
        boolean hasBtSettingsScreen();
        boolean hasDeviceSettingsScreen();
        void openDeviceSettings(BluetoothDevice device, String name);
        void refreshPill();
        void toast(String text);
    }

    private static final int MAX_LISTED_DEVICES = 6;
    private static final int MAX_FOUND_DEVICES = 8;

    private static final int ROW_LABEL = 0;
    private static final int ROW_INFO = 1;
    private static final int ROW_DEVICE = 2;
    private static final int ROW_ACTION = 3;

    private static final int ACT_PAIR_NEW = 1;
    private static final int ACT_SETTINGS = 2;
    private static final int ACT_SEARCH_AGAIN = 3;
    private static final int ACT_PAIR_DEVICE = 4;
    private static final int ACT_AUDIO_TOGGLE = 5;
    private static final int ACT_OPEN_DEVICE = 6;
    private static final int ACT_DEVICE_SETTINGS = 7;
    private static final int ACT_OPEN_SETTINGS = 8;

    private static final int MUTED = 0x73FFFFFF;
    private static final long AUDIO_TIMEOUT_MS = 20_000L;
    private static final String UUID_A2DP_SINK = "0000110b-0000-1000-8000-00805f9b34fb";

    private static final class Row {
        final int type;
        final int action;
        final int iconRes;
        final String text;
        final String status;
        final int statusColor;
        final BluetoothDevice device;

        Row(int type, int action, int iconRes, String text, String status,
            int statusColor, BluetoothDevice device) {
            this.type = type;
            this.action = action;
            this.iconRes = iconRes;
            this.text = text;
            this.status = status;
            this.statusColor = statusColor;
            this.device = device;
        }

        boolean selectable() { return type == ROW_ACTION; }
    }

    private final Activity activity;
    private final Host host;
    private final float density;

    private FrameLayout overlay;
    private LinearLayout card;
    private LinearLayout column;

    private final List<Row> rows = new ArrayList<>();
    private int selected = -1;
    private boolean scanMode;
    private boolean discovering;
    private BluetoothDevice pairing;
    private final Set<String> failedPairs = new HashSet<>();
    private final LinkedHashMap<String, BluetoothDevice> found = new LinkedHashMap<>();
    private final Set<String> aclConnected = new HashSet<>();
    private final Map<Integer, BluetoothProfile> proxies = new HashMap<>();
    private final Map<String, Integer> pendingAudio = new HashMap<>();
    private final Map<String, Runnable> audioTimeouts = new HashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean btSettingsScreen;
    private boolean deviceSettingsScreen;
    private BluetoothDevice detail;
    private BroadcastReceiver receiver;

    BluetoothPanel(Activity activity, Host host, float density) {
        this.activity = activity;
        this.host = host;
        this.density = density;
    }

    boolean isVisible() {
        return overlay != null && overlay.getVisibility() == View.VISIBLE;
    }

    void show() {
        if (overlay == null) build();
        FrameLayout ov = overlay;
        LinearLayout c = card;
        if (ov == null || c == null) return;
        scanMode = false;
        pairing = null;
        failedPairs.clear();
        found.clear();
        clearPendingAudio();
        detail = null;
        btSettingsScreen = host.hasBtSettingsScreen();
        deviceSettingsScreen = host.hasDeviceSettingsScreen();
        selected = -1;
        register();
        rebuild();
        anchorUnderPill(c);
        host.onPanelShown();
        ov.setVisibility(View.VISIBLE);
        ov.bringToFront();
        ov.requestFocus();
        c.animate().cancel();
        c.setAlpha(0f);
        c.setScaleX(0.94f);
        c.setScaleY(0.86f);
        c.setTranslationY(-dp(6));
        c.post(() -> {
            if (c != card) return;
            c.setPivotX(c.getWidth());
            c.setPivotY(0f);
            c.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                    .setDuration(160).setInterpolator(host.menuIn()).withLayer().start();
        });
    }

    void hide() {
        final FrameLayout ov = overlay;
        final LinearLayout c = card;
        if (ov == null || ov.getVisibility() != View.VISIBLE) return;
        stopDiscovery();
        clearPendingAudio();
        unregister();
        if (c != null) {
            c.animate().cancel();
            c.animate().alpha(0f).scaleX(0.96f).scaleY(0.9f).translationY(-dp(4))
                    .setDuration(110).setInterpolator(host.menuOut()).withLayer()
                    .withEndAction(() -> {
                        if (ov != overlay) return;
                        ov.setVisibility(View.GONE);
                        host.onPanelHidden();
                    })
                    .start();
        } else {
            ov.setVisibility(View.GONE);
            host.onPanelHidden();
        }
        View a = host.anchor();
        if (a != null) a.requestFocus();
    }

    void destroy() {
        stopDiscovery();
        clearPendingAudio();
        unregister();
        FrameLayout ov = overlay;
        if (ov != null) {
            FrameLayout r = host.root();
            if (r != null) r.removeView(ov);
        }
        overlay = null;
        card = null;
        column = null;
    }

    boolean handleKeyDown(int kc) {
        switch (kc) {
            case KeyEvent.KEYCODE_DPAD_UP:
                moveSelection(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                moveSelection(+1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                activate(selected);
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (scanMode) leaveScanMode();
                else if (detail != null) leaveDetail();
                else hide();
                return true;
            default:
                return !host.isLetThroughKey(kc);
        }
    }

    private void build() {
        FrameLayout r = host.root();
        if (r == null) return;
        FrameLayout ov = new FrameLayout(activity) {
            @Override public boolean onTouchEvent(MotionEvent ev) {
                if (ev.getAction() == MotionEvent.ACTION_DOWN) {
                    LinearLayout c = card;
                    if (c != null) {
                        float x = ev.getX(), y = ev.getY();
                        if (x < c.getX() || x > c.getX() + c.getWidth()
                                || y < c.getY() || y > c.getY() + c.getHeight()) {
                            hide();
                            return true;
                        }
                    }
                }
                return super.onTouchEvent(ev);
            }
        };
        ov.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        ov.setVisibility(View.GONE);
        ov.setClickable(true);
        ov.setFocusable(true);

        LinearLayout c = new LinearLayout(activity);
        c.setOrientation(LinearLayout.VERTICAL);
        EdgeGlassStyle.applyPanel(c, density);
        c.setClipChildren(false);
        c.setClipToPadding(false);

        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        c.addView(col, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.TOP | Gravity.END;
        c.setLayoutParams(cardLp);
        ov.addView(c);
        r.addView(ov);
        overlay = ov;
        card = c;
        column = col;
    }

    private void anchorUnderPill(View c) {
        int top = dp(78);
        int right = dp(20);
        View a = host.anchor();
        FrameLayout r = host.root();
        if (a != null && r != null && a.getWidth() > 0) {
            int[] aLoc = new int[2];
            int[] rLoc = new int[2];
            a.getLocationOnScreen(aLoc);
            r.getLocationOnScreen(rLoc);
            top = aLoc[1] - rLoc[1] + a.getHeight() + dp(4);
            right = r.getWidth() - (aLoc[0] - rLoc[0] + a.getWidth());
            if (right < dp(8)) right = dp(8);
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) c.getLayoutParams();
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.topMargin = top;
        lp.rightMargin = right;
        c.setLayoutParams(lp);
    }

    // ── Row model ───────────────────────────────────────────────────────

    private void rebuild() {
        Row prev = (selected >= 0 && selected < rows.size()) ? rows.get(selected) : null;
        rows.clear();
        if (detail != null && !isBonded(detail)) detail = null;
        if (scanMode) buildScanRows();
        else if (detail != null) buildDetailRows(detail);
        else buildListRows();
        selected = -1;
        if (prev != null) {
            for (int i = 0; i < rows.size(); i++) {
                Row r = rows.get(i);
                if (r.selectable() && r.action == prev.action && sameDevice(r.device, prev.device)) {
                    selected = i;
                    break;
                }
            }
        }
        if (selected < 0) selected = firstSelectable();
        render();
    }

    private void buildListRows() {
        BluetoothAdapter a = adapter();
        if (!hasConnectPermission()) {
            rows.add(info(str(R.string.bt_panel_need_permission)));
        } else if (a == null || !isEnabled(a)) {
            rows.add(info(str(R.string.bt_panel_off)));
        } else {
            List<BluetoothDevice> connected = new ArrayList<>();
            List<BluetoothDevice> idle = new ArrayList<>();
            Set<String> live = connectedAddresses(a);
            for (BluetoothDevice d : bonded(a)) {
                if (live.contains(d.getAddress())) connected.add(d);
                else idle.add(d);
            }
            int shown = 0;
            int total = connected.size() + idle.size();
            if (!connected.isEmpty()) {
                rows.add(label(str(R.string.bt_panel_connected)));
                for (BluetoothDevice d : connected) {
                    if (shown++ >= MAX_LISTED_DEVICES) break;
                    rows.add(pairedRow(d, true));
                }
            }
            if (!idle.isEmpty() && shown < MAX_LISTED_DEVICES) {
                rows.add(label(str(R.string.bt_panel_paired)));
                for (BluetoothDevice d : idle) {
                    if (shown++ >= MAX_LISTED_DEVICES) break;
                    rows.add(pairedRow(d, false));
                }
            }
            if (total == 0) rows.add(info(str(R.string.bt_panel_no_devices)));
            else if (total > MAX_LISTED_DEVICES) {
                rows.add(info(activity.getString(R.string.bt_panel_more,
                        total - MAX_LISTED_DEVICES)));
            }
            rows.add(action(ACT_PAIR_NEW, R.drawable.ic_btp_add, str(R.string.bt_panel_pair_new)));
        }
        rows.add(settingsRow());
    }

    private Row settingsRow() {
        return btSettingsScreen
                ? action(ACT_SETTINGS, R.drawable.ic_btp_settings, str(R.string.bt_panel_settings))
                : action(ACT_OPEN_SETTINGS, R.drawable.ic_btp_settings,
                        str(R.string.bt_panel_open_settings));
    }

    private Row pairedRow(BluetoothDevice d, boolean connected) {
        Integer target = pendingAudio.get(d.getAddress());
        if (target != null) {
            return device(d, str(target == BluetoothProfile.STATE_CONNECTED
                    ? R.string.bt_status_connecting : R.string.bt_status_disconnecting),
                    MUTED, ROW_ACTION, ACT_OPEN_DEVICE);
        }
        return device(d, str(connected ? R.string.bt_status_connected : R.string.bt_status_not_connected),
                connected ? EdgeGlassStyle.VALUE_ACCENT : MUTED, ROW_ACTION, ACT_OPEN_DEVICE);
    }

    private void buildDetailRows(BluetoothDevice d) {
        BluetoothAdapter a = adapter();
        boolean connected = a != null && connectedAddresses(a).contains(d.getAddress());
        Row header = pairedRow(d, connected);
        rows.add(new Row(ROW_DEVICE, 0, header.iconRes, header.text, header.status,
                header.statusColor, d));
        boolean any = false;
        Integer target = pendingAudio.get(d.getAddress());
        boolean a2dpUp = a2dpState(d) == BluetoothProfile.STATE_CONNECTED;
        if (target != null) {
            rows.add(action(ACT_AUDIO_TOGGLE, R.drawable.ic_btp_searching,
                    str(target == BluetoothProfile.STATE_CONNECTED
                            ? R.string.bt_status_connecting : R.string.bt_status_disconnecting)));
            any = true;
        } else if (proxies.containsKey(BluetoothProfile.A2DP)
                && (a2dpUp || (!connected && isAudioSink(d)))) {
            rows.add(action(ACT_AUDIO_TOGGLE, a2dpUp ? R.drawable.ic_bt_off : R.drawable.ic_bt_connected,
                    str(a2dpUp ? R.string.bt_action_disconnect : R.string.bt_action_connect)));
            any = true;
        } else if (!connected && isInputDevice(d)) {
            rows.add(info(activity.getString(R.string.bt_detail_press_button, nameOf(d))));
        }
        if (deviceSettingsScreen) {
            rows.add(action(ACT_DEVICE_SETTINGS, R.drawable.ic_btp_settings,
                    str(R.string.bt_detail_rename_unpair)));
            any = true;
        }
        if (!any) rows.add(settingsRow());
    }

    private void buildScanRows() {
        rows.add(label(str(R.string.bt_panel_available)));
        if (discovering) {
            rows.add(new Row(ROW_INFO, 0, R.drawable.ic_btp_searching,
                    str(R.string.bt_panel_searching), null, 0, null));
        } else if (found.isEmpty()) {
            rows.add(info(str(R.string.bt_panel_none_found)));
        }
        for (BluetoothDevice d : found.values()) {
            String status;
            int color = MUTED;
            if (sameDevice(d, pairing)) status = str(R.string.bt_status_pairing);
            else if (failedPairs.contains(d.getAddress())) status = str(R.string.bt_status_pair_failed);
            else {
                status = str(R.string.bt_status_pair);
                color = EdgeGlassStyle.VALUE_ACCENT;
            }
            rows.add(device(d, status, color, ROW_ACTION, ACT_PAIR_DEVICE));
        }
        if (!discovering) {
            rows.add(action(ACT_SEARCH_AGAIN, R.drawable.ic_btp_searching,
                    str(R.string.bt_panel_search_again)));
        }
    }

    private Row label(String t) { return new Row(ROW_LABEL, 0, 0, t, null, 0, null); }

    private Row info(String t) { return new Row(ROW_INFO, 0, 0, t, null, 0, null); }

    private Row action(int act, int icon, String t) {
        return new Row(ROW_ACTION, act, icon, t, null, 0, null);
    }

    private Row device(BluetoothDevice d, String status, int color, int type, int act) {
        return new Row(type, act, iconFor(d), nameOf(d), status, color, d);
    }

    // ── Rendering ───────────────────────────────────────────────────────

    private void render() {
        LinearLayout col = column;
        if (col == null) return;
        col.removeAllViews();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            View v = (r.type == ROW_LABEL) ? labelView(r) : rowView(r);
            if (r.selectable()) {
                final int idx = i;
                v.setClickable(true);
                v.setOnClickListener(x -> {
                    x.playSoundEffect(SoundEffectConstants.CLICK);
                    selected = idx;
                    paintSelection();
                    activate(idx);
                });
            }
            col.addView(v);
        }
        paintSelection();
    }

    private View labelView(Row r) {
        TextView t = new TextView(activity);
        t.setText(r.text);
        t.setTextColor(0x80FFFFFF);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setAllCaps(true);
        t.setLetterSpacing(0.08f);
        t.setPadding(dp(10), dp(6), dp(10), dp(3));
        return t;
    }

    private View rowView(Row r) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(7), dp(10), dp(7));
        row.setMinimumWidth(dp(250));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(EdgeGlassStyle.rowRadiusPx(density));
        bg.setColor(EdgeGlassStyle.ROW_IDLE_BG);
        row.setBackground(bg);

        if (r.iconRes != 0) {
            ImageView icon = new ImageView(activity);
            icon.setImageResource(r.iconRes);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(16), dp(16));
            ilp.setMarginEnd(dp(10));
            row.addView(icon, ilp);
        }

        TextView label = new TextView(activity);
        label.setText(r.text);
        boolean info = r.type == ROW_INFO;
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, info ? 11 : 13);
        label.setTypeface(Typeface.create(info ? "sans-serif" : "sans-serif-medium",
                Typeface.NORMAL));
        label.setSingleLine(!info);
        label.setMaxLines(info ? 2 : 1);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setMaxWidth(dp(info ? 250 : 190));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(label, llp);

        if (r.status != null) {
            TextView st = new TextView(activity);
            st.setText(r.status);
            st.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            st.setSingleLine(true);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            slp.setMarginStart(dp(12));
            row.addView(st, slp);
        }

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(2);
        row.setLayoutParams(rlp);
        return row;
    }

    private void paintSelection() {
        LinearLayout col = column;
        if (col == null) return;
        for (int i = 0; i < col.getChildCount() && i < rows.size(); i++) {
            Row r = rows.get(i);
            View v = col.getChildAt(i);
            if (!(v instanceof LinearLayout)) continue;
            LinearLayout row = (LinearLayout) v;
            boolean sel = i == selected;
            if (row.getBackground() instanceof GradientDrawable) {
                ((GradientDrawable) row.getBackground())
                        .setColor(sel ? EdgeGlassStyle.SELECTED_PILL : EdgeGlassStyle.ROW_IDLE_BG);
            }
            int text = sel ? EdgeGlassStyle.SELECTED_TEXT
                    : (r.type == ROW_INFO ? 0x99FFFFFF : EdgeGlassStyle.IDLE_TEXT);
            for (int k = 0; k < row.getChildCount(); k++) {
                View child = row.getChildAt(k);
                if (child instanceof ImageView) {
                    ((ImageView) child).setColorFilter(text);
                } else if (child instanceof TextView) {
                    boolean isStatus = r.status != null && k == row.getChildCount() - 1;
                    ((TextView) child).setTextColor(
                            isStatus ? (sel ? EdgeGlassStyle.SELECTED_TEXT : r.statusColor) : text);
                }
            }
        }
    }

    private void moveSelection(int dir) {
        int n = rows.size();
        if (n == 0 || selected < 0) return;
        int i = selected;
        for (int step = 0; step < n; step++) {
            i = (i + dir + n) % n;
            if (rows.get(i).selectable()) {
                selected = i;
                paintSelection();
                return;
            }
        }
    }

    private int firstSelectable() {
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).selectable()) return i;
        return -1;
    }

    // ── Actions ─────────────────────────────────────────────────────────

    private void activate(int idx) {
        if (idx < 0 || idx >= rows.size()) return;
        Row r = rows.get(idx);
        if (!r.selectable()) return;
        switch (r.action) {
            case ACT_PAIR_NEW:
                if (canScanInPanel()) enterScanMode();
                else {
                    hide();
                    host.openBtPairing();
                }
                break;
            case ACT_SETTINGS:
                hide();
                host.openBtSettings();
                break;
            case ACT_SEARCH_AGAIN:
                found.clear();
                failedPairs.clear();
                startDiscovery();
                break;
            case ACT_PAIR_DEVICE:
                pair(r.device);
                break;
            case ACT_AUDIO_TOGGLE:
                toggleAudio(detail);
                break;
            case ACT_OPEN_DEVICE:
                enterDetail(r.device);
                break;
            case ACT_DEVICE_SETTINGS: {
                BluetoothDevice d = detail;
                if (d == null) break;
                String name = nameOf(d);
                hide();
                host.openDeviceSettings(d, name);
                break;
            }
            case ACT_OPEN_SETTINGS:
                hide();
                host.openSystemSettings();
                break;
            default:
                break;
        }
    }

    private void enterDetail(BluetoothDevice d) {
        if (d == null) return;
        detail = d;
        selected = -1;
        rebuild();
    }

    private void leaveDetail() {
        BluetoothDevice d = detail;
        detail = null;
        selected = -1;
        rebuild();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (r.action == ACT_OPEN_DEVICE && sameDevice(r.device, d)) {
                selected = i;
                paintSelection();
                break;
            }
        }
    }

    private void toggleAudio(BluetoothDevice d) {
        if (d == null) return;
        final String addr = d.getAddress();
        if (pendingAudio.containsKey(addr)) return;
        final boolean connect = a2dpState(d) != BluetoothProfile.STATE_CONNECTED;
        if (!invokeA2dp(proxies.get(BluetoothProfile.A2DP), connect, d)) {
            host.toast(activity.getString(connect ? R.string.toast_bt_connect_failed
                    : R.string.toast_bt_disconnect_failed, nameOf(d)));
            return;
        }
        final int target = connect ? BluetoothProfile.STATE_CONNECTED
                : BluetoothProfile.STATE_DISCONNECTED;
        pendingAudio.put(addr, target);
        Runnable timeout = () -> {
            audioTimeouts.remove(addr);
            if (pendingAudio.remove(addr) == null || !isVisible()) return;
            if (a2dpState(d) != target) {
                host.toast(activity.getString(connect ? R.string.toast_bt_connect_failed
                        : R.string.toast_bt_disconnect_failed, nameOf(d)));
            }
            host.refreshPill();
            rebuild();
        };
        audioTimeouts.put(addr, timeout);
        handler.postDelayed(timeout, AUDIO_TIMEOUT_MS);
        rebuild();
    }

    private void onA2dpState(BluetoothDevice d, int state, int prev) {
        if (d == null) return;
        String addr = d.getAddress();
        Integer target = pendingAudio.get(addr);
        if (target == null) return;
        if (state == target) {
            clearPendingAudio(addr);
        } else if (target == BluetoothProfile.STATE_CONNECTED
                && state == BluetoothProfile.STATE_DISCONNECTED
                && prev == BluetoothProfile.STATE_CONNECTING) {
            clearPendingAudio(addr);
            host.toast(activity.getString(R.string.toast_bt_connect_failed, nameOf(d)));
        }
    }

    private void clearPendingAudio(String addr) {
        pendingAudio.remove(addr);
        Runnable t = audioTimeouts.remove(addr);
        if (t != null) handler.removeCallbacks(t);
    }

    private void clearPendingAudio() {
        for (Runnable t : audioTimeouts.values()) handler.removeCallbacks(t);
        audioTimeouts.clear();
        pendingAudio.clear();
    }

    /** BluetoothA2dp.connect/disconnect are hidden but on the unrestricted
     *  hidden-API list through Android 14, gated only by the normal Bluetooth
     *  permission. Any failure just reports false. */
    @SuppressLint({"DiscouragedPrivateApi", "PrivateApi"})
    private static boolean invokeA2dp(BluetoothProfile proxy, boolean connect, BluetoothDevice d) {
        if (!(proxy instanceof BluetoothA2dp)) return false;
        try {
            Object r = BluetoothA2dp.class
                    .getMethod(connect ? "connect" : "disconnect", BluetoothDevice.class)
                    .invoke(proxy, d);
            return Boolean.TRUE.equals(r);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private boolean canScanInPanel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false;
        return activity.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN)
                        == PackageManager.PERMISSION_GRANTED
                && hasConnectPermission();
    }

    private void enterScanMode() {
        scanMode = true;
        found.clear();
        failedPairs.clear();
        pairing = null;
        selected = -1;
        startDiscovery();
    }

    private void leaveScanMode() {
        stopDiscovery();
        scanMode = false;
        pairing = null;
        selected = -1;
        rebuild();
    }

    private void startDiscovery() {
        BluetoothAdapter a = adapter();
        boolean started = false;
        if (a != null) {
            try {
                if (a.isDiscovering()) a.cancelDiscovery();
                started = a.startDiscovery();
            } catch (SecurityException ignored) {
            }
        }
        discovering = started;
        rebuild();
    }

    private void stopDiscovery() {
        if (!discovering) return;
        discovering = false;
        BluetoothAdapter a = adapter();
        if (a == null) return;
        try {
            a.cancelDiscovery();
        } catch (SecurityException ignored) {
        }
    }

    private void pair(BluetoothDevice d) {
        if (d == null || pairing != null) return;
        stopDiscovery();
        failedPairs.remove(d.getAddress());
        boolean ok;
        try {
            ok = d.createBond();
        } catch (SecurityException e) {
            ok = false;
        }
        if (ok) pairing = d;
        else failedPairs.add(d.getAddress());
        rebuild();
    }

    // ── Broadcasts and profile proxies ──────────────────────────────────

    private void register() {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                onBroadcast(intent);
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothDevice.ACTION_NAME_CHANGED);
        f.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        f.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        f.addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED");
        try {
            activity.registerReceiver(receiver, f);
        } catch (RuntimeException e) {
            receiver = null;
        }
        openProxies();
    }

    private void unregister() {
        BroadcastReceiver r = receiver;
        receiver = null;
        if (r != null) {
            try { activity.unregisterReceiver(r); } catch (RuntimeException ignored) { }
        }
        BluetoothAdapter a = adapter();
        for (Map.Entry<Integer, BluetoothProfile> e : proxies.entrySet()) {
            if (a == null) break;
            try { a.closeProfileProxy(e.getKey(), e.getValue()); } catch (RuntimeException ignored) { }
        }
        proxies.clear();
        aclConnected.clear();
    }

    private void openProxies() {
        BluetoothAdapter a = adapter();
        if (a == null || !hasConnectPermission()) return;
        int[] profiles = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                ? new int[] { BluetoothProfile.A2DP, BluetoothProfile.HEADSET, BluetoothProfile.LE_AUDIO }
                : new int[] { BluetoothProfile.A2DP, BluetoothProfile.HEADSET };
        BluetoothProfile.ServiceListener l = new BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (receiver == null) {
                    try { a.closeProfileProxy(profile, proxy); } catch (RuntimeException ignored) { }
                    return;
                }
                proxies.put(profile, proxy);
                if (!scanMode) rebuild();
            }
            @Override public void onServiceDisconnected(int profile) {
                proxies.remove(profile);
            }
        };
        for (int p : profiles) {
            try { a.getProfileProxy(activity, l, p); } catch (RuntimeException ignored) { }
        }
    }

    private void onBroadcast(Intent intent) {
        if (!isVisible()) return;
        String act = intent.getAction();
        if (act == null) return;
        BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        switch (act) {
            case BluetoothDevice.ACTION_ACL_CONNECTED:
                if (d != null) aclConnected.add(d.getAddress());
                break;
            case BluetoothDevice.ACTION_ACL_DISCONNECTED:
                if (d != null) aclConnected.remove(d.getAddress());
                break;
            case BluetoothAdapter.ACTION_DISCOVERY_STARTED:
                discovering = true;
                break;
            case BluetoothAdapter.ACTION_DISCOVERY_FINISHED:
                discovering = false;
                break;
            case BluetoothDevice.ACTION_FOUND:
            case BluetoothDevice.ACTION_NAME_CHANGED:
                if (d != null && scanMode && !found.containsKey(d.getAddress())
                        && found.size() < MAX_FOUND_DEVICES
                        && !TextUtils.isEmpty(safeName(d)) && !isBonded(d)) {
                    found.put(d.getAddress(), d);
                }
                break;
            case BluetoothDevice.ACTION_BOND_STATE_CHANGED:
                onBondChanged(d, intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,
                        BluetoothDevice.BOND_NONE));
                break;
            case BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED:
                onA2dpState(d, intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1),
                        intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1));
                break;
            case BluetoothAdapter.ACTION_STATE_CHANGED:
                if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                        != BluetoothAdapter.STATE_ON) {
                    scanMode = false;
                    discovering = false;
                    detail = null;
                }
                break;
            default:
                break;
        }
        host.refreshPill();
        rebuild();
    }

    private void onBondChanged(BluetoothDevice d, int state) {
        if (d == null || !sameDevice(d, pairing)) return;
        if (state == BluetoothDevice.BOND_BONDED) {
            pairing = null;
            scanMode = false;
            host.toast(activity.getString(R.string.toast_bt_paired, nameOf(d)));
        } else if (state == BluetoothDevice.BOND_NONE) {
            pairing = null;
            failedPairs.add(d.getAddress());
        }
    }

    // ── Bluetooth reads ─────────────────────────────────────────────────

    private BluetoothAdapter adapter() {
        BluetoothManager bm = activity.getSystemService(BluetoothManager.class);
        return bm != null ? bm.getAdapter() : null;
    }

    private boolean hasConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || activity.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isEnabled(BluetoothAdapter a) {
        try {
            return a.isEnabled();
        } catch (SecurityException e) {
            return false;
        }
    }

    private List<BluetoothDevice> bonded(BluetoothAdapter a) {
        List<BluetoothDevice> out = new ArrayList<>();
        try {
            Set<BluetoothDevice> s = a.getBondedDevices();
            if (s != null) out.addAll(s);
        } catch (SecurityException ignored) {
        }
        return out;
    }

    private boolean isBonded(BluetoothDevice d) {
        try {
            return d.getBondState() == BluetoothDevice.BOND_BONDED;
        } catch (SecurityException e) {
            return false;
        }
    }

    private int a2dpState(BluetoothDevice d) {
        BluetoothProfile p = proxies.get(BluetoothProfile.A2DP);
        if (p == null || d == null) return BluetoothProfile.STATE_DISCONNECTED;
        try {
            return p.getConnectionState(d);
        } catch (RuntimeException e) {
            return BluetoothProfile.STATE_DISCONNECTED;
        }
    }

    private boolean isAudioSink(BluetoothDevice d) {
        ParcelUuid[] uuids;
        try {
            uuids = d.getUuids();
        } catch (SecurityException e) {
            uuids = null;
        }
        if (uuids != null && uuids.length > 0) {
            for (ParcelUuid u : uuids) {
                if (u != null && UUID_A2DP_SINK.equalsIgnoreCase(u.toString())) return true;
            }
            return false;
        }
        BluetoothClass c = bluetoothClass(d);
        return c != null && c.getMajorDeviceClass() == BluetoothClass.Device.Major.AUDIO_VIDEO;
    }

    private boolean isInputDevice(BluetoothDevice d) {
        BluetoothClass c = bluetoothClass(d);
        return c != null && c.getMajorDeviceClass() == BluetoothClass.Device.Major.PERIPHERAL;
    }

    private static BluetoothClass bluetoothClass(BluetoothDevice d) {
        try {
            return d.getBluetoothClass();
        } catch (SecurityException e) {
            return null;
        }
    }

    private Set<String> connectedAddresses(BluetoothAdapter a) {
        Set<String> out = new HashSet<>(aclConnected);
        for (BluetoothProfile p : proxies.values()) {
            try {
                for (BluetoothDevice d : p.getConnectedDevices()) out.add(d.getAddress());
            } catch (RuntimeException ignored) {
            }
        }
        BluetoothManager bm = activity.getSystemService(BluetoothManager.class);
        if (bm != null) {
            try {
                for (BluetoothDevice d : bm.getConnectedDevices(BluetoothProfile.GATT)) {
                    out.add(d.getAddress());
                }
            } catch (RuntimeException ignored) {
            }
        }
        for (BluetoothDevice d : bonded(a)) {
            if (out.contains(d.getAddress())) continue;
            try {
                Object r = d.getClass().getMethod("isConnected").invoke(d);
                if (Boolean.TRUE.equals(r)) out.add(d.getAddress());
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        return out;
    }

    private String safeName(BluetoothDevice d) {
        try {
            return d.getName();
        } catch (SecurityException e) {
            return null;
        }
    }

    private String nameOf(BluetoothDevice d) {
        String n = safeName(d);
        return TextUtils.isEmpty(n) ? str(R.string.bt_unknown_device) : n;
    }

    private int iconFor(BluetoothDevice d) {
        BluetoothClass c = bluetoothClass(d);
        if (c == null) return R.drawable.ic_bt;
        int major = c.getMajorDeviceClass();
        int dev = c.getDeviceClass();
        if (major == BluetoothClass.Device.Major.AUDIO_VIDEO) {
            if (dev == BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER
                    || dev == BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO
                    || dev == BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO
                    || dev == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO) {
                return R.drawable.ic_btp_speaker;
            }
            return R.drawable.ic_btp_headphones;
        }
        if (major == BluetoothClass.Device.Major.PERIPHERAL) {
            int minor = dev & 0x3C;
            if ((dev & 0x40) != 0) return R.drawable.ic_btp_keyboard;
            if (minor == 0x08 || minor == 0x04) return R.drawable.ic_btp_gamepad;
            return R.drawable.ic_btp_remote;
        }
        return R.drawable.ic_bt;
    }

    private static boolean sameDevice(BluetoothDevice a, BluetoothDevice b) {
        if (a == null || b == null) return a == b;
        return a.getAddress().equals(b.getAddress());
    }

    private String str(int res) { return activity.getString(res); }

    private int dp(int v) { return Math.round(v * density); }
}
