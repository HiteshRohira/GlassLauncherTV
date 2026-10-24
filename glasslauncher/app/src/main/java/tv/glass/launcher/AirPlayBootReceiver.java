package tv.glass.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the AirPlay monitor after boot and after Glass updates, so the
 *  failure banner works even before Home is first shown. No-op when the
 *  receiver app is not installed. */
public final class AirPlayBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            AirPlayMonitorService.start(ctx, AirPlayMonitorService.ACTION_BOOT);
        }
    }
}
