package tv.glass.launcher;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.*;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class AuditRegressionTest {
    private static Object field(Object target,String name)throws Exception {
        Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);
    }
    private static void invoke(Object target,String name)throws Exception {
        Method m=target.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(target);
    }
    @SuppressWarnings("unchecked")
    private static List<AppInfo> apps(LauncherActivity a)throws Exception {
        return (List<AppInfo>)field(a,"appList");
    }
    private static void ready(ActivityScenario<LauncherActivity> scenario)throws Exception {
        long until=SystemClock.elapsedRealtime()+10000;
        while(SystemClock.elapsedRealtime()<until){
            AtomicReference<Boolean> ok=new AtomicReference<>(false);
            scenario.onActivity(a->{try{
                ok.set(!apps(a).isEmpty()&&!((java.util.concurrent.atomic.AtomicBoolean)field(a,"appsLoading")).get());
            }catch(Exception e){throw new AssertionError(e);}});
            if(ok.get())return;
            SystemClock.sleep(50);
        }
        fail("App reconciliation did not complete");
    }
    @Test public void samePackageUpdatedActivityAndLabelAreReconciled()throws Exception {
        try(ActivityScenario<LauncherActivity> scenario=ActivityScenario.launch(LauncherActivity.class)){
            ready(scenario);
            AtomicReference<AppInfo> expected=new AtomicReference<>();
            scenario.onActivity(a->{try{
                List<AppInfo> all=apps(a);
                for(int i=0;i<all.size();i++){
                    AppInfo good=all.get(i);
                    if(good.tvInputId!=null||good.component==null)continue;
                    expected.set(good);
                    all.set(i,new AppInfo(good.packageName,good.sourceLabel+" outdated",
                        new ComponentName(good.packageName,good.packageName+".RemovedActivity"),good.ri));
                    invoke(a,"loadApps");break;
                }
            }catch(Exception e){throw new AssertionError(e);}});
            assertNotNull(expected.get());ready(scenario);
            scenario.onActivity(a->{try{
                AppInfo old=expected.get();
                AppInfo refreshed=apps(a).stream().filter(x->x.packageName.equals(old.packageName)).findFirst().get();
                assertEquals(old.component,refreshed.component);
                assertEquals(old.sourceLabel,refreshed.sourceLabel);
            }catch(Exception e){throw new AssertionError(e);}});
        }
    }
    @Test public void publicationRebasesOnOrderSavedAfterScan()throws Exception {
        Context ctx=getInstrumentation().getTargetContext();
        SharedPreferences prefs=ctx.getSharedPreferences("glass_launcher",Context.MODE_PRIVATE);
        String before=prefs.getString("app_order",null);
        try(ActivityScenario<LauncherActivity> scenario=ActivityScenario.launch(LauncherActivity.class)){
            ready(scenario);
            AtomicReference<List<String>> expected=new AtomicReference<>();
            scenario.onActivity(a->{try{
                invoke(a,"loadApps");
                // The worker finishes sorting while its UI publication waits behind this callback.
                ((ExecutorService)field(a,"appExecutor")).submit(()->{}).get(3,TimeUnit.SECONDS);
                List<String> order=new ArrayList<>();
                for(AppInfo app:apps(a))order.add(app.packageName);
                assertTrue(order.size()>=2);
                java.util.Collections.swap(order,0,1);
                prefs.edit().putString("app_order",String.join(",",order)).commit();
                Method reorder=LauncherActivity.class.getDeclaredMethod("applyStoredOrder",List.class);
                reorder.setAccessible(true);reorder.invoke(a,apps(a));
                expected.set(order);
            }catch(Exception e){throw new AssertionError(e);}});
            ready(scenario);
            scenario.onActivity(a->{try{
                List<String> actual=new ArrayList<>();for(AppInfo app:apps(a))actual.add(app.packageName);
                assertEquals(expected.get(),actual);
                assertEquals(String.join(",",actual),prefs.getString("app_order",""));
            }catch(Exception e){throw new AssertionError(e);}});
        }finally{
            SharedPreferences.Editor editor=prefs.edit();
            if(before==null)editor.remove("app_order");else editor.putString("app_order",before);
            editor.commit();
        }
    }
    @Test public void revocationDoesNotDependOnFileTimestamp()throws Exception {
        Context context=getInstrumentation().getTargetContext();
        File file=new File(context.getNoBackupFilesDir(),"phone-remote-access.json");
        byte[] previous=file.exists()?Files.readAllBytes(file.toPath()):null;
        GlassRemoteServer server=null;
        try{
            String token=GlassRemoteAccess.update(context,b->b.pair(GlassRemoteAccess.RANDOM,System.currentTimeMillis()));
            long stamp=file.lastModified();
            server=new GlassRemoteServer(context,()->{});
            Method cookie=GlassRemoteServer.class.getDeclaredMethod("hasCookie",String.class);cookie.setAccessible(true);
            assertEquals(true,cookie.invoke(server,"glass_remote="+token));
            long revision=GlassRemoteAccess.load(context).revision;
            GlassRemoteAccess.forgetAll(context);
            assertTrue(GlassRemoteAccess.load(context).revision>revision);
            assertTrue(file.setLastModified(stamp));
            assertEquals(false,cookie.invoke(server,"glass_remote="+token));
        }finally{
            if(server!=null)server.close();
            if(previous==null)Files.deleteIfExists(file.toPath());else Files.write(file.toPath(),previous);
        }
    }
    @Test public void terminalRemoteCloseDoesNotWaitForCommandMonitor()throws Exception {
        TvRemoteClient client=new TvRemoteClient(getInstrumentation().getTargetContext());
        CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1);
        Thread command=new Thread(()->{synchronized(client){held.countDown();try{release.await();}catch(InterruptedException ignored){}}});
        command.start();assertTrue(held.await(2,TimeUnit.SECONDS));
        try{
            long now=SystemClock.elapsedRealtime();client.close();
            assertTrue("close blocked the caller",SystemClock.elapsedRealtime()-now<200);
            try{client.connect();fail("closed client reconnected");}catch(java.io.IOException expected){}
        }finally{release.countDown();command.join(2000);}
    }
}
