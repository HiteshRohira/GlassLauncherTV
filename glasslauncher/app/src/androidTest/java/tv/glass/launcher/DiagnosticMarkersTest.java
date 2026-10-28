package tv.glass.launcher;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import io.github.jqssun.airplay.diagnostics.PerfTrace;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class DiagnosticMarkersTest {
    @Test public void readinessAndInputMatchRealFrames() throws Exception {
        assumeTrue("enable MiragePerf before starting this test process",PerfTrace.ENABLED);
        PerfTrace.snapshot(true);
        try(ActivityScenario<LauncherActivity> scenario=ActivityScenario.launch(LauncherActivity.class)){
            await("first_frame_complete");
            await("home_ready_frame_submitted");
            await("visible_artwork_frame_submitted");
            scenario.onActivity(a->{
                View anchor=described(a.getWindow().getDecorView(),"Control Center");
                assertNotNull(anchor);anchor.requestFocus();anchor.performClick();
            });
            await("control_center_ready_frame_submitted");
            getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);
            await("input_frame_submitted");
            scenario.onActivity(a->{
                View tile=a.getWindow().getDecorView().findViewWithTag("bluetooth");
                assertNotNull(tile);tile.performClick();
            });
            await("bluetooth_panel_ready_frame_submitted");
            getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            awaitInputs(2);getInstrumentation().waitForIdleSync();SystemClock.sleep(50);
            getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            awaitInputs(3);
            String json=PerfTrace.snapshot(false);
            assertFalse("no typed text is recorded",json.contains("password"));
            assertFalse("no device addresses are recorded",json.contains("address"));
            JSONArray events=new JSONObject(json).getJSONArray("events");
            for(int i=0;i<events.length();i++){
                JSONObject e=events.getJSONObject(i);
                assertEquals("valid matched frame: "+e,0,e.getInt("result"));
                assertTrue("nonnegative duration: "+e,e.getLong("end_ns")>=e.getLong("start_ns"));
            }
        }
    }
    private static void await(String stage)throws Exception{
        long end=SystemClock.uptimeMillis()+10_000;
        while(SystemClock.uptimeMillis()<end){
            JSONArray events=new JSONObject(PerfTrace.snapshot(false)).getJSONArray("events");
            for(int i=0;i<events.length();i++)if(stage.equals(events.getJSONObject(i).getString("stage")))return;
            SystemClock.sleep(25);
        }
        fail("missing diagnostic marker "+stage+": "+PerfTrace.snapshot(false));
    }
    private static void awaitInputs(int count)throws Exception{
        long end=SystemClock.uptimeMillis()+10_000;
        while(SystemClock.uptimeMillis()<end){
            JSONArray events=new JSONObject(PerfTrace.snapshot(false)).getJSONArray("events");int found=0;
            for(int i=0;i<events.length();i++)if("input_frame_submitted".equals(events.getJSONObject(i).getString("stage")))found++;
            if(found>=count)return;
            SystemClock.sleep(25);
        }
        fail("missing native-key frame submission: "+PerfTrace.snapshot(false));
    }
    private static View described(View v,String description){
        if(v.isShown()&&description.contentEquals(v.getContentDescription()==null?"":v.getContentDescription())&&v.getWidth()<500)return v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){
            View found=described(((ViewGroup)v).getChildAt(i),description);if(found!=null)return found;
        }
        return null;
    }
}
