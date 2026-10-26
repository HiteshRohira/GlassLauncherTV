package tv.glass.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class MirageIdentityTest {
    @Test public void newPackageRegistersHomeAndRemoteKeyboard() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("Mirage release or debug package", context.getPackageName().equals("tv.mirage.launcher")
                || context.getPackageName().equals("tv.mirage.launcher.debug"));
        PackageManager pm = context.getPackageManager();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setPackage(context.getPackageName());
        ResolveInfo resolved = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        assertNotNull("Mirage is a Home candidate", resolved);
        ActivityInfo activity = resolved.activityInfo;
        assertEquals(context.getPackageName(), activity.packageName);
        assertEquals(LauncherActivity.class.getName(), activity.name);
        assertTrue(pm.getApplicationLabel(activity.applicationInfo).toString().contains("Mirage"));
        ServiceInfo keyboard = pm.getServiceInfo(
                new ComponentName(context, GlassRemoteInputMethod.class), 0);
        assertEquals("android.permission.BIND_INPUT_METHOD", keyboard.permission);
        assertEquals("Mirage phone keyboard", keyboard.loadLabel(pm).toString());
    }
}
