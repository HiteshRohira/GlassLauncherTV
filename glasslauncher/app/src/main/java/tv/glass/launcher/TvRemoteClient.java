package tv.glass.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import dadb.Dadb;
import dadb.AdbShellResponse;

/** Authenticated local ADB for fixed keys; text never enters a shell command. */
final class TvRemoteClient implements AutoCloseable {
    private final Context context;
    private final SharedPreferences prefs;
    private Dadb adb;
    volatile boolean connected,pairing;
    volatile String message="Enable TV controls once and approve Android's authorization on the TV.";
    TvRemoteClient(Context c){context=c.getApplicationContext();prefs=context.getSharedPreferences("phone_remote_native",Context.MODE_PRIVATE);}
    boolean isPaired(){return prefs.getBoolean("local_authorized",false);}
    boolean keyboardReady(){return GlassRemoteInputMethod.hasEditor();}
    synchronized void startPairing()throws Exception{connect();}
    synchronized void connect()throws Exception{
        if(connected)return;close();pairing=true;message="Approve the Android debugging authorization on your TV.";
        try{adb=GoogleTvSetup.connect(context);AdbShellResponse response=adb.shell("echo glass-remote-ready");
            if(response.getExitCode()!=0 || !response.getAllOutput().contains("glass-remote-ready"))throw new IOException("TV controls could not be enabled.");
            connected=true;prefs.edit().putBoolean("local_authorized",true).apply();message="Connected";
        }catch(Exception e){close();throw new IOException("TV controls could not connect. Approve Android's authorization on the TV, then retry.",e);}
        finally{pairing=false;}
    }
    synchronized void key(int code)throws IOException{
        if(!connected||adb==null)throw new IOException("Reconnect TV controls first.");
        if(!allowedKey(code))throw new IOException("Unknown button.");
        try{check(adb.shell("input keyevent "+code));}
        catch(IOException e){close();throw e;}
    }
    static boolean allowedKey(int code){
        switch(code){case 3:case 4:case 19:case 20:case 21:case 22:case 23:case 24:case 25:case 66:case 67:case 85:case 89:case 90:case 164:return true;default:return false;}
    }
    synchronized void sendText(String value)throws IOException{
        if(!connected||adb==null)throw new IOException("Reconnect TV controls first.");
        if(value.isEmpty()||value.length()>2048)throw new IOException("Enter between 1 and 2048 characters.");
        String previous=Settings.Secure.getString(context.getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD);
        ComponentName prior=ComponentName.unflattenFromString(previous==null?"":previous);
        String own=new ComponentName(context,GlassRemoteInputMethod.class).flattenToShortString();
        if(prior==null)throw new IOException("The TV's keyboard is unavailable.");
        String restore=prior.flattenToShortString();
        if(!restore.matches("[A-Za-z0-9._]+/[A-Za-z0-9._$]+"))throw new IOException("The TV's keyboard could not be selected.");
        GlassRemoteInputMethod.Delivery delivery=GlassRemoteInputMethod.prepare(value);
        boolean switched=false;
        try{
            check(adb.shell("ime enable '"+own+"'"));
            check(adb.shell("ime set '"+own+"'"));switched=true;
            if(!delivery.done.await(4,TimeUnit.SECONDS)||!delivery.success)throw new IOException("Select a text field on the TV, then send your text again.");
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Text input was interrupted.",e);}
        finally{
            GlassRemoteInputMethod.cancel(delivery);
            if(switched && !own.equals(restore)){
                try{check(adb.shell("ime set '"+restore+"'"));}catch(Exception e){throw new IOException("Your usual TV keyboard could not be restored. Select it in TV keyboard settings.",e);}
            }
        }
    }
    private static void check(AdbShellResponse response)throws IOException{
        if(response.getExitCode()!=0)throw new IOException("The TV could not complete that action.");
    }
    @Override public synchronized void close(){connected=false;if(adb!=null)try{adb.close();}catch(Exception ignored){}adb=null;}
}
