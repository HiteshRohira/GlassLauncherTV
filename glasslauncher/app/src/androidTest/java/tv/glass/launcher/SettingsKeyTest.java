package tv.glass.launcher;

import static org.junit.Assert.*;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class SettingsKeyTest {
    @Test public void keyFilterLeavesNavigationAndMediaAloneAndConsumesHolds() {
        SettingsKeyService service=new SettingsKeyService();
        for(int code:new int[]{KeyEvent.KEYCODE_HOME,KeyEvent.KEYCODE_BACK,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_VOLUME_UP,KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,KeyEvent.KEYCODE_MENU}) {
            assertFalse(SettingsKeyService.isSettingsKey(code));
            assertFalse(service.onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,code)));
        }
        for(int code:new int[]{4077,KeyEvent.KEYCODE_SETTINGS}) {
            assertTrue(SettingsKeyService.isSettingsKey(code));
            assertTrue(service.onKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,code)));
            assertTrue(service.onKeyEvent(new KeyEvent(0,0,KeyEvent.ACTION_DOWN,code,1)));
        }
    }
    @Test public void overlayContainsFocusAndBackFinishes() {
        getInstrumentation().setInTouchMode(false);
        try(ActivityScenario<ControlCenterActivity> scenario=ActivityScenario.launch(ControlCenterActivity.class)) {
            SystemClock.sleep(600);
            scenario.onActivity(a->{
                ControlCenterPanel p=panel(a.getWindow().getDecorView());assertNotNull(p);assertTrue(p.isVisible());
                for(int key:new int[]{19,20,21,22})for(int i=0;i<10;i++)press(a,key);
                View focus=a.getCurrentFocus();assertNotNull(focus);
                View cur=focus;while(cur!=p && cur.getParent() instanceof View)cur=(View)cur.getParent();assertSame(p,cur);
                press(a,KeyEvent.KEYCODE_BACK);assertTrue(a.isFinishing());
            });
        }
    }
    @Test public void bluetoothBackReturnsToControlsWithoutFinishing() {
        getInstrumentation().setInTouchMode(false);
        try(ActivityScenario<ControlCenterActivity> scenario=ActivityScenario.launch(ControlCenterActivity.class)) {
            SystemClock.sleep(600);
            scenario.onActivity(a->{press(a,KeyEvent.KEYCODE_DPAD_RIGHT);press(a,KeyEvent.KEYCODE_DPAD_CENTER);});
            SystemClock.sleep(400);
            scenario.onActivity(a->{assertFalse(panel(a.getWindow().getDecorView()).isVisible());press(a,KeyEvent.KEYCODE_BACK);});
            SystemClock.sleep(400);
            scenario.onActivity(a->{assertTrue(panel(a.getWindow().getDecorView()).isVisible());assertFalse(a.isFinishing());});
        }
    }
    @Test public void enabledServiceMetadataAndSettingsCallbackRestoreExistingTask() throws Exception {
        android.app.Instrumentation inst=getInstrumentation();
        android.app.UiAutomation automation=inst.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        android.content.Context context=inst.getTargetContext();
        String previous=android.provider.Settings.Secure.getString(context.getContentResolver(),"enabled_accessibility_services");
        String enabled=android.provider.Settings.Secure.getString(context.getContentResolver(),"accessibility_enabled");
        String component=context.getPackageName()+"/tv.glass.launcher.SettingsKeyService";
        try(ActivityScenario<LauncherActivity> home=ActivityScenario.launch(LauncherActivity.class)) {
            automation=inst.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            shell(automation,"settings put secure enabled_accessibility_services "+component);
            shell(automation,"settings put secure accessibility_enabled 1");
            long deadline=SystemClock.uptimeMillis()+8000;
            boolean ready=false;
            while(SystemClock.uptimeMillis()<deadline) {
                android.view.accessibility.AccessibilityManager am=context.getSystemService(android.view.accessibility.AccessibilityManager.class);
                for(android.accessibilityservice.AccessibilityServiceInfo info:am.getEnabledAccessibilityServiceList(-1))
                    if(info.getResolveInfo().serviceInfo.name.equals(SettingsKeyService.class.getName())) {
                        assertTrue((info.getCapabilities()&android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS)!=0);
                        assertEquals(0,info.getCapabilities()&android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT);
                        ready=true;
                    }
                if(ready)break;SystemClock.sleep(100);
            }
            assertTrue("Settings-key service connected: "+shell(automation,"dumpsys accessibility"),ready);
            // Android's injected shell keys bypass InputReader's accessibility filter.
            // Exercise the connected service's callback contract with a real app context;
            // the physical TCL event must still be checked on the actual remote.
            class AttachedService extends SettingsKeyService {
                AttachedService() { attachBaseContext(context); }
            }
            inst.runOnMainSync(()->assertTrue(new AttachedService().onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,4077))));
            java.util.concurrent.atomic.AtomicReference<ControlCenterActivity> overlay=new java.util.concurrent.atomic.AtomicReference<>();
            deadline=SystemClock.uptimeMillis()+5000;
            while(SystemClock.uptimeMillis()<deadline && overlay.get()==null) {
                inst.runOnMainSync(()->{for(android.app.Activity a:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))if(a instanceof ControlCenterActivity)overlay.set((ControlCenterActivity)a);});
                if(overlay.get()==null)SystemClock.sleep(100);
            }
            assertNotNull("Settings callback opened the overlay",overlay.get());
            inst.runOnMainSync(()->press(overlay.get(),KeyEvent.KEYCODE_BACK));
            SystemClock.sleep(400);
            home.onActivity(a->assertTrue("Original task regained focus",a.hasWindowFocus()));
        } finally {
            shell(automation,previous==null?"settings delete secure enabled_accessibility_services":"settings put secure enabled_accessibility_services "+previous);
            shell(automation,enabled==null?"settings delete secure accessibility_enabled":"settings put secure accessibility_enabled "+enabled);
        }
    }
    private static String shell(android.app.UiAutomation automation,String command)throws Exception {
        try(android.os.ParcelFileDescriptor fd=automation.executeShellCommand(command);java.io.FileInputStream stream=new java.io.FileInputStream(fd.getFileDescriptor())) { java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;while((count=stream.read(buffer))!=-1)output.write(buffer,0,count);return output.toString("UTF-8"); }
    }
    private static void press(ControlCenterActivity a,int key){a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,key));a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,key));}
    private static ControlCenterPanel panel(View v){if(v instanceof ControlCenterPanel)return (ControlCenterPanel)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){ControlCenterPanel p=panel(((ViewGroup)v).getChildAt(i));if(p!=null)return p;}return null;}
}
