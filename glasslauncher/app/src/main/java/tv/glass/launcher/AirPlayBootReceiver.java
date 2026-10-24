package tv.glass.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts Glass's background host (built-in AirPlay receiver and phone
 *  remote) after boot and after Glass updates, before Home is first shown. */
public final class AirPlayBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            AirPlayMonitorService.start(ctx, AirPlayMonitorService.ACTION_BOOT);
        }
    }
}
