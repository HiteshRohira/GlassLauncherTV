package tv.glass.launcher;

import android.app.Activity;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.FrameMetrics;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import io.github.jqssun.airplay.diagnostics.PerfTrace;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.function.BooleanSupplier;

/** Opt-in markers bound to API 29's rendering/submission callback.
 * Submission is not physical display scanout. Older/software renderers
 * explicitly report these markers unavailable rather than inventing a time.
 */
final class WindowPerformanceProbe implements ViewTreeObserver.OnPreDrawListener,
        Window.OnFrameMetricsAvailableListener {
    private static final int LIMIT=32;
    private final Activity activity;
    private final View root;
    private final long createdNs;
    private final BooleanSupplier homeReady;
    private final HandlerThread thread=new HandlerThread("MiragePerf");
    private final Handler handler;
    private final ArrayDeque<Marker> waiting=new ArrayDeque<>(), pending=new ArrayDeque<>();
    private final ArrayList<Panel> panels=new ArrayList<>();
    private boolean initialHome=true, firstFrame=true, firstSubmission=true;
    private long resumeNs, lostCallbacks, lostMarkers, unsupported;
    private volatile boolean closed;
    private boolean metricsAttached=true;
    private static final class Marker {
        final String name;final long start;final int code;
        boolean done;Runnable timeout;
        Marker(String name,long start,int code){this.name=name;this.start=start;this.code=code;}
    }
    private static final class Panel {
        final String name;final long start;final BooleanSupplier visible,ready;
        Runnable timeout;
        Panel(String name,long start,BooleanSupplier visible,BooleanSupplier ready){
            this.name=name;this.start=start;this.visible=visible;this.ready=ready;
        }
    }
    WindowPerformanceProbe(Activity activity,long createdNs,BooleanSupplier homeReady){
        this.activity=activity;this.createdNs=createdNs;this.homeReady=homeReady;
        root=activity.getWindow().getDecorView();
        thread.start();handler=new Handler(thread.getLooper());
        root.getViewTreeObserver().addOnPreDrawListener(this);
        activity.getWindow().addOnFrameMetricsAvailableListener(this,handler);
    }
    private final Runnable homeTimeout=()->{synchronized(this){
        if(!closed&&resumeNs!=0){PerfTrace.end("home_readiness_timeout",resumeNs,0,2);resumeNs=0;lostMarkers++;}
    }};
    synchronized void resume(){resumeNs=System.nanoTime();handler.removeCallbacks(homeTimeout);handler.postDelayed(homeTimeout,10_000);}
    synchronized void panel(String name,BooleanSupplier visible,BooleanSupplier ready){
        if(panels.size()==LIMIT){
            Panel old=panels.remove(0);handler.removeCallbacks(old.timeout);lostMarkers++;
            PerfTrace.end(old.name+"_readiness_overflow",old.start,0,2);
        }
        Panel p=new Panel(name,System.nanoTime(),visible,ready);
        p.timeout=()->{synchronized(this){if(panels.remove(p)){lostMarkers++;PerfTrace.end(p.name+"_readiness_timeout",p.start,0,2);}}};
        panels.add(p);handler.postDelayed(p.timeout,10_000);
    }
    void key(KeyEvent event){
        int code=event.getKeyCode();
        if(event.getAction()!=KeyEvent.ACTION_DOWN || !((code>=19&&code<=23)||code==4||code==66))return;
        long source=event.getEventTime()*1_000_000L;
        PerfTrace.record("input_received",source,System.nanoTime(),code,0,0);
        synchronized(this){enqueue(new Marker("input_frame_submitted",source,code));}
    }
    private void enqueue(Marker m){
        if(pending.size()==LIMIT)complete(pending.getFirst(),System.nanoTime(),2);
        m.timeout=()->complete(m,System.nanoTime(),2);
        waiting.addLast(m);pending.addLast(m);handler.postDelayed(m.timeout,10_000);
    }
    private synchronized void complete(Marker m,long submittedNs,int result){
        if(m.done)return;
        m.done=true;pending.remove(m);waiting.remove(m);handler.removeCallbacks(m.timeout);
        if(closed)return;
        if(result!=0)lostMarkers++;
        PerfTrace.record(m.name,m.start,submittedNs,m.code,result,0);
        if(result==0&&"home_ready_frame_submitted".equals(m.name))
            root.post(()->{if(!closed)activity.reportFullyDrawn();});
    }
    @Override public synchronized boolean onPreDraw(){
        if(closed)return true;
        if(firstSubmission){firstSubmission=false;enqueue(new Marker("first_frame_submitted",createdNs,0));}
        if(resumeNs!=0 && homeReady.getAsBoolean()){
            enqueue(new Marker(initialHome?"home_ready_frame_submitted":"home_resume_ready_frame_submitted",initialHome?createdNs:resumeNs,0));
            if(initialHome)enqueue(new Marker("visible_artwork_frame_submitted",createdNs,0));
            initialHome=false;resumeNs=0;handler.removeCallbacks(homeTimeout);
        }
        for(Iterator<Panel> it=panels.iterator();it.hasNext();){
            Panel p=it.next();
            if(!p.visible.getAsBoolean()){
                handler.removeCallbacks(p.timeout);PerfTrace.end(p.name+"_canceled",p.start,0,1);it.remove();
            }else if(p.ready.getAsBoolean()){
                handler.removeCallbacks(p.timeout);enqueue(new Marker(p.name+"_ready_frame_submitted",p.start,0));it.remove();
            }
        }
        if(!waiting.isEmpty()){
            ArrayList<Marker> batch=new ArrayList<>(waiting);waiting.clear();
            if(Build.VERSION.SDK_INT>=29 && root.isHardwareAccelerated()){
                root.getViewTreeObserver().registerFrameCommitCallback(()->{
                    long now=System.nanoTime();for(Marker m:batch)complete(m,now,0);
                });
            }else{
                unsupported+=batch.size();
                for(Marker m:batch)complete(m,System.nanoTime(),3);
            }
        }
        return true;
    }
    @Override public synchronized void onFrameMetricsAvailable(Window window,FrameMetrics metric,int dropped){
        if(closed)return;
        lostCallbacks+=dropped;
        if(firstFrame){
            firstFrame=false;
            long end=metric.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)+metric.getMetric(FrameMetrics.TOTAL_DURATION);
            PerfTrace.record("first_frame_complete",createdNs,end,0,0,0);
            // Only the first frame uses FrameMetrics. Input/panel markers use
            // commit callbacks; avoid copying unused metrics on every frame.
            detachMetrics();
        }
    }
    synchronized void dump(PrintWriter out){
        out.println("MIRAGE_WINDOW_PERF method=frame_commit_callback pending_inputs="+waiting.size()
                +" pending_submissions="+(pending.size()-waiting.size())+" pending_panels="+panels.size()
                +" home_waiting="+(resumeNs!=0)+" dropped_callbacks="+lostCallbacks+" dropped_markers="+lostMarkers+" unsupported_markers="+unsupported);
    }
    private synchronized void detachMetrics(){
        if(!metricsAttached)return;
        metricsAttached=false;
        activity.getWindow().removeOnFrameMetricsAvailableListener(this);
    }
    void close(){
        closed=true;
        if(root.getViewTreeObserver().isAlive())root.getViewTreeObserver().removeOnPreDrawListener(this);
        detachMetrics();
        thread.quitSafely();
    }
}
