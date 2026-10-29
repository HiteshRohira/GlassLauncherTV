package tv.glass.launcher;

import static org.junit.Assert.*;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@RunWith(AndroidJUnit4.class)
public class SystemControlPagesTest {
 @Test public void secondSheetsKeepFocusAndBackReturnsToOpeningTile()throws Exception {
  getInstrumentation().setInTouchMode(false);
  try(ActivityScenario<ControlCenterActivity> scenario=ActivityScenario.launch(ControlCenterActivity.class)) {
   SystemClock.sleep(400);
   for(String page:new String[]{"network","input"}) {
    scenario.onActivity(a->{ControlCenterPanel p=panel(a.getWindow().getDecorView());if(page.equals("network"))p.openNetwork();else p.openInputs();});
    SystemClock.sleep(500);
    scenario.onActivity(a->{ControlCenterPanel p=panel(a.getWindow().getDecorView());assertTrue(p.isDetailVisible(page));for(int i=0;i<30;i++)press(a,KeyEvent.KEYCODE_DPAD_DOWN);View f=a.getCurrentFocus();assertNotNull(f);while(f!=p && f.getParent() instanceof View)f=(View)f.getParent();assertSame(p,f);press(a,KeyEvent.KEYCODE_BACK);});
    SystemClock.sleep(350);
    scenario.onActivity(a->{assertFalse(panel(a.getWindow().getDecorView()).isDetailVisible(page));assertFalse(a.isFinishing());assertEquals(page,a.getCurrentFocus().getTag());});
   }
  }
 }
 @Test public void wifiJoinsPreserveUnicodeAndClearPasswordAndDeduplicateScans()throws Exception {
  AtomicReference<SystemControlPages> pages=new AtomicReference<>();AtomicReference<View> view=new AtomicReference<>();List<JSONObject> requests=Collections.synchronizedList(new ArrayList<>());
  try(ActivityScenario<ControlCenterActivity> scenario=ActivityScenario.launch(ControlCenterActivity.class)) {
   scenario.onActivity(a->{a.getSharedPreferences("phone_remote_native",0).edit().putBoolean("local_authorized",true).commit();SystemControlPages p=new SystemControlPages(a,request->{requests.add(new JSONObject(request.toString()));return fixture();});pages.set(p);View root=p.wifi(()->fail("No external settings should launch"));view.set(root);showPage(a,root);});
   SystemClock.sleep(300);
   capture("wifi-networks");
   scenario.onActivity(a->{assertEquals(1,countTag(view.get(),"scan-Café ' $()"));tag(view.get(),"scan-Café ' $()").performClick();});
   capture("wifi-password-empty");
   AtomicReference<EditText> field=new AtomicReference<>();
   scenario.onActivity(a->{EditText edit=edit(view.get());field.set(edit);assertFalse(edit.isSaveEnabled());edit.setText("test-'$()secret");tag(view.get(),"connect").performClick();assertEquals("",edit.getText().toString());});
   SystemClock.sleep(300);
   assertEquals("Café ' $()",requests.get(1).getString("ssid"));assertEquals("test-'$()secret",requests.get(1).getString("password"));assertEquals("wpa2",requests.get(1).getString("security"));
   scenario.onActivity(a->{for(Object value:a.getSharedPreferences("phone_remote_native",0).getAll().values())assertFalse(String.valueOf(value).contains("test-'$()secret"));pages.get().close();});
  }
 }
 @Test public void wifiFormBackClearsSecretAndStaleReplyDoesNotPopulateNewPage()throws Exception {
  AtomicReference<SystemControlPages> pages=new AtomicReference<>();AtomicReference<View> view=new AtomicReference<>();CountDownLatch release=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
  try(ActivityScenario<ControlCenterActivity> scenario=ActivityScenario.launch(ControlCenterActivity.class)) {
   scenario.onActivity(a->{a.getSharedPreferences("phone_remote_native",0).edit().putBoolean("local_authorized",true).commit();SystemControlPages p=new SystemControlPages(a,request->{if(calls.incrementAndGet()==2)release.await(3,TimeUnit.SECONDS);return fixture();});pages.set(p);View root=p.wifi(()->{});view.set(root);showPage(a,root);});
   SystemClock.sleep(250);
   scenario.onActivity(a->{tag(view.get(),"scan-Café ' $()").performClick();EditText field=edit(view.get());field.setText("should-be-cleared");assertTrue(pages.get().back());assertEquals("",field.getText().toString());tag(view.get(),"refresh").performClick();pages.get().cancel();});
   release.countDown();SystemClock.sleep(250);
   scenario.onActivity(a->{assertNull(tag(view.get(),"refresh"));pages.get().close();});
  } finally {release.countDown();}
 }
 @Test public void securityDoesNotMistakeUnsupportedEncryptionForOpen() {
  assertEquals("open",SystemControlPages.security("[ESS]"));assertEquals("wpa2",SystemControlPages.security("[RSN-PSK-CCMP][ESS]"));assertEquals("wpa3",SystemControlPages.security("[RSN-SAE-CCMP][ESS]"));
  for(String flags:new String[]{"[EAP][ESS]","[WEP][ESS]","[OWE][ESS]","[WAPI][ESS]","[WAPI-PSK-SMS4][ESS]","[DPP][ESS]",""})assertEquals("unsupported",SystemControlPages.security(flags));
 }
 @Test public void wifiConfigurationsUseThePlatformsSecurityPolicy()throws Exception {
  if(android.os.Build.VERSION.SDK_INT<30)return;
  JSONObject request=new JSONObject().put("ssid","Café ' $()").put("password","synthetic-password").put("security","wpa3");
  android.net.wifi.WifiConfiguration config=WifiControlBridge.configuration(request);
  assertTrue(config.allowedKeyManagement.get(android.net.wifi.WifiConfiguration.KeyMgmt.SAE));
  assertTrue(config.allowedProtocols.get(android.net.wifi.WifiConfiguration.Protocol.RSN));
  assertFalse(config.allowedProtocols.get(android.net.wifi.WifiConfiguration.Protocol.WPA));
  java.lang.reflect.Field pmf=android.net.wifi.WifiConfiguration.class.getDeclaredField("requirePmf");pmf.setAccessible(true);assertTrue("SAE requires protected management frames",pmf.getBoolean(config));
  request.put("security","wpa2");config=WifiControlBridge.configuration(request);assertTrue(config.allowedKeyManagement.get(android.net.wifi.WifiConfiguration.KeyMgmt.WPA_PSK));
  request.put("security","open");config=WifiControlBridge.configuration(request);assertTrue(config.allowedKeyManagement.get(android.net.wifi.WifiConfiguration.KeyMgmt.NONE));assertNull(config.preSharedKey);
  request.put("security","unknown");try{WifiControlBridge.configuration(request);fail("Unknown security must not become open");}catch(java.io.IOException expected){}
 }
 private static void showPage(ControlCenterActivity activity,View page){
  try{java.lang.reflect.Method method=ControlCenterPanel.class.getDeclaredMethod("openDetail",String.class,String.class,View.class);method.setAccessible(true);method.invoke(panel(activity.getWindow().getDecorView()),"network","Wi-Fi",page);}catch(Exception e){throw new AssertionError(e);}
 }
 private static void capture(String name)throws Exception {
  try(android.os.ParcelFileDescriptor fd=getInstrumentation().getUiAutomation().executeShellCommand("screencap -p /data/local/tmp/mirage-"+name+".png");java.io.FileInputStream stream=new java.io.FileInputStream(fd.getFileDescriptor())){byte[] buffer=new byte[4096];while(stream.read(buffer)!=-1){}}
 }
 private static JSONObject fixture()throws Exception {return new JSONObject().put("enabled",true).put("currentId",0).put("ssid","Living room").put("linkMbps",117).put("saved",new JSONArray().put(new JSONObject().put("id",0).put("ssid","Living room"))).put("networks",new JSONArray().put(new JSONObject().put("ssid","Café ' $()").put("rssi",-60).put("capabilities","[WPA2-PSK][ESS]")).put(new JSONObject().put("ssid","Café ' $()").put("rssi",-40).put("capabilities","[WPA2-PSK][ESS]")));}
 private static void press(ControlCenterActivity a,int key){a.dispatchKeyEvent(new android.view.KeyEvent(0,key));a.dispatchKeyEvent(new android.view.KeyEvent(1,key));}
 private static ControlCenterPanel panel(View v){if(v instanceof ControlCenterPanel)return(ControlCenterPanel)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){ControlCenterPanel p=panel(((ViewGroup)v).getChildAt(i));if(p!=null)return p;}return null;}
 private static View tag(View v,String tag){return v.findViewWithTag(tag);}
 private static int countTag(View v,String tag){int count=tag.equals(v.getTag())?1:0;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)count+=countTag(((ViewGroup)v).getChildAt(i),tag);return count;}
 private static EditText edit(View v){if(v instanceof EditText)return(EditText)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){EditText e=edit(((ViewGroup)v).getChildAt(i));if(e!=null)return e;}return null;}
}
