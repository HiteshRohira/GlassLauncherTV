package tv.glass.launcher;

import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.Toast;

/** A separate translucent task: dismissing controls returns to the app beneath, never Home. */
public final class ControlCenterActivity extends Activity {
    private FrameLayout root;
    private ControlCenterPanel controls;
    private BluetoothPanel bluetooth;
    private boolean swapping;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        public void run() { if (controls != null) controls.refresh(); handler.postDelayed(this, 2000); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        getWindow().setDimAmount(0f);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        root = new FrameLayout(this); setContentView(root);
        controls = new ControlCenterPanel(this, new ControlCenterPanel.Host() {
            public String deviceName() { String n=Settings.Global.getString(getContentResolver(),Settings.Global.DEVICE_NAME); return n==null?"This TV":n; }
            public String networkName() { return "Network"; }
            public String networkStatus() {
                ConnectivityManager cm=getSystemService(ConnectivityManager.class);
                NetworkCapabilities caps=cm==null?null:cm.getNetworkCapabilities(cm.getActiveNetwork());
                return caps!=null?"Connected":"Not connected";
            }
            public AirPlayMonitorService.Snapshot air() { return AirPlayMonitorService.read(ControlCenterActivity.this); }
            public boolean airLegacyInstalled() { return AirPlayMonitorService.legacyReceiverInstalled(ControlCenterActivity.this); }
            public String bluetoothSummary() { return "Devices & audio"; }
            public String remoteSummary() { return GlassRemoteAccess.enabled(ControlCenterActivity.this)?"Use your iPhone":"Off"; }
            public void network() { open(Settings.ACTION_WIFI_SETTINGS); }
            public void inputs() { open("android.settings.TV_INPUT_SETTINGS"); }
            public void settings() { open(Settings.ACTION_SETTINGS); }
            public boolean usesPictureTile() { return true; }
            public void picture() { open(Settings.ACTION_DISPLAY_SETTINGS); }
            public void bluetooth() { showBluetooth(); }
            public void setAirEnabled(boolean on) { AirPlayMonitorService.setEnabled(ControlCenterActivity.this,on); }
            public void setAirDebug(boolean on) { AirPlayMonitorService.setDebug(ControlCenterActivity.this,on); }
            public void restartAir() { AirPlayMonitorService.start(ControlCenterActivity.this,AirPlayMonitorService.ACTION_RESTART); }
            public void showAirPlayer() { startActivity(new Intent(ControlCenterActivity.this,tv.glass.airplay.AirPlayPlayerActivity.class)); finish(); }
            public View appearance() { return new View(ControlCenterActivity.this); }
            public View remote() { return GlassRemotePanel.create(ControlCenterActivity.this); }
            public void shown(boolean visible) { if (!visible && !swapping) finish(); }
        });
        root.addView(controls); controls.show(null,"network");
    }
    private void open(String action) {
        try { startActivity(new Intent(action)); finish(); }
        catch (RuntimeException e) { Toast.makeText(this,"This control is unavailable on this TV",Toast.LENGTH_SHORT).show(); }
    }
    private void showBluetooth() {
        if (bluetooth==null) bluetooth=new BluetoothPanel(this,new BluetoothPanel.Host() {
            public FrameLayout root() { return root; }
            public View anchor() { return controls.findFocus(); }
            public Interpolator menuIn() { return new DecelerateInterpolator(); }
            public Interpolator menuOut() { return new DecelerateInterpolator(); }
            public boolean isLetThroughKey(int code) { return deviceKey(code); }
            public void onPanelShown() { }
            public void onPanelHidden() { if(!isFinishing())controls.show(null,"bluetooth",false); }
            public void openBtSettings() { open(Settings.ACTION_BLUETOOTH_SETTINGS); }
            public void openBtPairing() { open("com.tcl.settings.action.BT_PAIR"); }
            public void openSystemSettings() { open(Settings.ACTION_SETTINGS); }
            public boolean hasBtSettingsScreen() { return new Intent(Settings.ACTION_BLUETOOTH_SETTINGS).resolveActivity(getPackageManager())!=null; }
            public boolean hasDeviceSettingsScreen() { return false; }
            public void openDeviceSettings(BluetoothDevice device,String name) { openBtSettings(); }
            public void refreshPill() { controls.refresh(); }
            public void toast(String text) { Toast.makeText(ControlCenterActivity.this,text,Toast.LENGTH_SHORT).show(); }
            public boolean returnsToControlCenter() { return true; }
            public String subtitle() { return "Devices & audio"; }
        },getResources().getDisplayMetrics().density);
        swapping=true; controls.hide(false); swapping=false; bluetooth.show(false);
    }
    private static boolean deviceKey(int code) {
        switch(code) {
            case KeyEvent.KEYCODE_VOLUME_UP: case KeyEvent.KEYCODE_VOLUME_DOWN: case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_POWER: case KeyEvent.KEYCODE_SLEEP: case KeyEvent.KEYCODE_WAKEUP: case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: case KeyEvent.KEYCODE_MEDIA_PLAY: case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP: case KeyEvent.KEYCODE_MEDIA_NEXT: case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND: case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: return true;
            default:return false;
        }
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int code=event.getKeyCode();
        if(deviceKey(code)) return super.dispatchKeyEvent(event);
        if(controls.dispatchAppearanceWidgetKey(event))return true;
        if(event.getAction()==KeyEvent.ACTION_DOWN) {
            if(bluetooth!=null && bluetooth.isVisible()) bluetooth.handleKeyDown(code);
            else controls.key(code);
        }
        return true;
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); finish(); }
    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onStop() { super.onStop(); if(!isChangingConfigurations())finish(); }
    @Override protected void onDestroy() { handler.removeCallbacks(refresh); if(bluetooth!=null)bluetooth.destroy(); super.onDestroy(); }
}
