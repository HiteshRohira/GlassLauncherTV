package io.github.jqssun.airplay.diagnostics;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayDeque;

/** Device-local, opt-in diagnostic markers. No files, uploads or per-event logs.
 * Enable before process start with `setprop log.tag.MiragePerf DEBUG`.
 * Normal release use creates no recorder or timing arrays.
 */
public final class PerfTrace {
    public static final boolean ENABLED = Log.isLoggable("MiragePerf", Log.DEBUG);
    private static final int LIMIT = 256;
    private static final class Entry {
        final String stage; final long start, end, uncertainty; final int code, result, id;
        Entry(String stage,long start,long end,int code,int result,int id,long uncertainty){
            this.stage=stage;this.start=start;this.end=end;this.code=code;this.result=result;this.id=id;this.uncertainty=uncertainty;
        }
    }
    private static final class State {
        final ArrayDeque<Entry> entries=new ArrayDeque<>(LIMIT);
        long dropped;
    }
    private static final class Holder { static final State STATE=new State(); }
    public static long begin(){return ENABLED?System.nanoTime():0;}
    public static void end(String stage,long start,int code,int result){
        if(ENABLED)record(stage,start,System.nanoTime(),code,result,0);
    }
    public static void record(String stage,long start,long end,int code,int result,int id){
        record(stage,start,end,code,result,id,0);
    }
    public static void record(String stage,long start,long end,int code,int result,int id,long uncertainty){
        if(!ENABLED)return;
        State s=Holder.STATE;
        synchronized(s){
            if(s.entries.size()==LIMIT){s.entries.removeFirst();s.dropped++;}
            s.entries.addLast(new Entry(stage,start,end,code,result,id,uncertainty));
        }
    }
    public static String snapshot(boolean reset){
        if(!ENABLED)return "{\"enabled\":false}";
        State s=Holder.STATE;
        synchronized(s){
            try{
                JSONArray events=new JSONArray();
                for(Entry e:s.entries)events.put(new JSONObject().put("stage",e.stage)
                        .put("start_ns",e.start).put("end_ns",e.end).put("code",e.code)
                        .put("result",e.result).put("id",e.id).put("uncertainty_ns",e.uncertainty));
                String json=new JSONObject().put("enabled",true).put("clock","CLOCK_MONOTONIC")
                        .put("now_ns",System.nanoTime()).put("dropped",s.dropped).put("events",events).toString();
                if(reset){s.entries.clear();s.dropped=0;}
                return json;
            }catch(org.json.JSONException impossible){return "{\"error\":\"encoding\"}";}
        }
    }
    private PerfTrace(){}
}
