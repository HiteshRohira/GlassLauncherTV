package tv.glass.launcher;

import android.inputmethodservice.InputMethodService;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import java.util.concurrent.CountDownLatch;

/** Momentary Unicode input bridge. Restores the user's keyboard after delivery. */
public final class GlassRemoteInputMethod extends InputMethodService {
    static final class Delivery {
        final String text;final CountDownLatch done=new CountDownLatch(1);
        volatile boolean success;
        Delivery(String value){text=value;}
    }
    private static volatile GlassRemoteInputMethod instance;
    private static Delivery pending;
    private volatile boolean editor;
    static synchronized Delivery prepare(String value){
        if(pending!=null)pending.done.countDown();pending=new Delivery(value);
        GlassRemoteInputMethod current=instance;if(current!=null)new android.os.Handler(android.os.Looper.getMainLooper()).post(current::deliver);
        return pending;
    }
    static synchronized void cancel(Delivery delivery){if(pending==delivery)pending=null;}
    static boolean hasEditor(){GlassRemoteInputMethod current=instance;return current!=null&&current.editor;}
    @Override public void onCreate(){super.onCreate();instance=this;}
    @Override public void onStartInput(EditorInfo info,boolean restarting){super.onStartInput(info,restarting);editor=info!=null&&info.inputType!=0;deliver();}
    @Override public void onFinishInput(){editor=false;super.onFinishInput();}
    @Override public boolean onEvaluateInputViewShown(){super.onEvaluateInputViewShown();return false;}
    @Override public void onDestroy(){if(instance==this)instance=null;super.onDestroy();}
    private void deliver(){
        Delivery delivery;synchronized(GlassRemoteInputMethod.class){delivery=pending;if(delivery==null||!editor)return;pending=null;}
        try{
            InputConnection connection=getCurrentInputConnection();
            if(connection!=null){connection.beginBatchEdit();try{delivery.success=connection.commitText(delivery.text,1);}finally{connection.endBatchEdit();}}
        }finally{delivery.done.countDown();}
    }
}
