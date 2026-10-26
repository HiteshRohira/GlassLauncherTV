package tv.glass.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import dadb.Dadb;
import dadb.AdbShellPacket;
import dadb.AdbShellResponse;
import dadb.AdbShellStream;

/** Authenticated local ADB for fixed keys; text never enters a shell command.
 *
 *  <p>Keys take the fastest path available, in order:
 *  <ol>
 *  <li>{@link KeyInjector}, a persistent shell-user process started once over
 *      local ADB: a few milliseconds per key, and real long presses;</li>
 *  <li>in this process, without ADB: volume, mute and media keys through
 *      {@link AudioManager}, and Home when ADB is not connected;</li>
 *  <li>{@code input keyevent}, which starts a Java runtime per press
 *      (roughly half a second on this TV).</li>
 *  </ol> */
final class TvRemoteClient implements AutoCloseable {
    private static final String TAG = "GlassRemote";
    /** Prior keyboard, written before Glass's IME is selected and removed once
     *  restored, so a crash mid-send can be repaired on the next connection. */
    private static final String IME_JOURNAL = "phone-remote-ime-restore";
    private static final long INJECTOR_START_MS = 5_000;
    private static final long INJECTOR_REPLY_MS = 1_500;
    private final Context context;
    private final SharedPreferences prefs;
    private final AudioManager audio;
    /** Serialises connecting, which may wait minutes for the TV-side prompt,
     *  without holding the lock that key presses need. */
    private final Object connectLock = new Object();
    private Dadb adb;
    private AdbShellStream injector;
    private BlockingQueue<String> injectorReplies;
    private long injectorAttemptAt;
    volatile boolean connected,pairing;
    volatile String message="Enable TV controls once and approve Android's authorization on the TV.";
    TvRemoteClient(Context c){context=c.getApplicationContext();prefs=context.getSharedPreferences("phone_remote_native",Context.MODE_PRIVATE);audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);}
    boolean isPaired(){return prefs.getBoolean("local_authorized",false);}
    boolean keyboardReady(){return GlassRemoteInputMethod.hasEditor();}
    void startPairing()throws Exception{connect();}
    void connect()throws Exception{
        synchronized(connectLock){
            if(connected)return;
            close();pairing=true;message="Approve the Android debugging authorization on your TV.";
            try{
                Dadb fresh=GoogleTvSetup.connect(context);
                AdbShellResponse response=fresh.shell("echo glass-remote-ready");
                if(response.getExitCode()!=0 || !response.getAllOutput().contains("glass-remote-ready")){try{fresh.close();}catch(Exception ignored){}throw new IOException("TV controls could not be enabled.");}
                synchronized(this){adb=fresh;connected=true;}
                prefs.edit().putBoolean("local_authorized",true).apply();message="Connected";
                recoverKeyboard();
                synchronized(this){startInjector();}
            }catch(Exception e){close();throw new IOException("TV controls could not connect. Approve Android's authorization on the TV, then retry.",e);}
            finally{pairing=false;}
        }
    }

    /** Presses a key. Volume and media keys work even without ADB. */
    void key(int code)throws IOException{press(code,false);}
    /** Holds a key long enough to count as a long press. */
    void longPress(int code)throws IOException{press(code,true);}

    private void press(int code,boolean hold)throws IOException{
        if(!allowedKey(code))throw new IOException("Unknown button.");
        if(!connected){
            if(!hold&&localKey(code,true))return;
            throw new IOException("Reconnect TV controls first.");
        }
        synchronized(this){
            if(!connected||adb==null){if(!hold&&localKey(code,true))return;throw new IOException("Reconnect TV controls first.");}
            if(injector!=null){
                if(injectorCommand((hold?"l ":"k ")+code,hold?INJECTOR_REPLY_MS+1_000:INJECTOR_REPLY_MS))return;
                Log.w(TAG,"Key injector stopped answering; falling back");
                stopInjector();
            }
            if(!hold&&localKey(code,false))return;
            try{check(adb.shell(hold?"input keyevent --longpress "+code:"input keyevent "+code));}
            catch(IOException e){close();throw e;}
            // The injector may have died with adbd or the freezer; retry now and then.
            if(injector==null&&SystemClock.elapsedRealtime()-injectorAttemptAt>60_000)startInjector();
        }
    }

    /** Keys handled in this process with public APIs. {@code noAdb}: also
     *  Home, which cannot be verified from here, so it is only used when the
     *  alternative is doing nothing. */
    private boolean localKey(int code,boolean noAdb){
        if(audio==null)return false;
        switch(code){
            case KeyEvent.KEYCODE_VOLUME_UP: audio.adjustSuggestedStreamVolume(AudioManager.ADJUST_RAISE,AudioManager.USE_DEFAULT_STREAM_TYPE,AudioManager.FLAG_SHOW_UI);return true;
            case KeyEvent.KEYCODE_VOLUME_DOWN: audio.adjustSuggestedStreamVolume(AudioManager.ADJUST_LOWER,AudioManager.USE_DEFAULT_STREAM_TYPE,AudioManager.FLAG_SHOW_UI);return true;
            case KeyEvent.KEYCODE_VOLUME_MUTE: audio.adjustSuggestedStreamVolume(AudioManager.ADJUST_TOGGLE_MUTE,AudioManager.USE_DEFAULT_STREAM_TYPE,AudioManager.FLAG_SHOW_UI);return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:case KeyEvent.KEYCODE_MEDIA_REWIND:case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:{
                long now=SystemClock.uptimeMillis();
                audio.dispatchMediaKeyEvent(new KeyEvent(now,now,KeyEvent.ACTION_DOWN,code,0));
                audio.dispatchMediaKeyEvent(new KeyEvent(now,now,KeyEvent.ACTION_UP,code,0));
                return true;
            }
            case KeyEvent.KEYCODE_HOME:
                if(!noAdb)return false;
                try{context.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return true;}
                catch(RuntimeException e){return false;}
            default:return false;
        }
    }

    /** Starts {@link KeyInjector} as the shell user from this APK. Failures are
     *  logged and leave the slower paths in charge. Caller holds the lock. */
    private void startInjector(){
        if(adb==null||injector!=null)return;
        injectorAttemptAt=SystemClock.elapsedRealtime();
        String apk=context.getApplicationInfo().sourceDir;
        if(apk==null||!apk.matches("/[A-Za-z0-9/._=~+-]+\\.apk")){Log.w(TAG,"Key injector: unexpected APK path");return;}
        try{
            AdbShellStream stream=adb.openShell("CLASSPATH='"+apk+"' exec app_process /system/bin "+KeyInjector.class.getName());
            BlockingQueue<String> replies=new ArrayBlockingQueue<>(64);
            Thread reader=new Thread(()->readLines(stream,replies),"remote-key-injector");
            reader.setDaemon(true);reader.start();
            String hello=replies.poll(INJECTOR_START_MS,TimeUnit.MILLISECONDS);
            if(!("ready "+KeyInjector.VERSION).equals(hello)){
                Log.w(TAG,"Key injector did not start: "+hello);
                try{stream.close();}catch(Exception ignored){}
                return;
            }
            injector=stream;injectorReplies=replies;
            Log.i(TAG,"Key injector ready");
        }catch(Exception e){Log.w(TAG,"Key injector unavailable",e);}
    }

    private static void readLines(AdbShellStream stream,BlockingQueue<String> lines){
        StringBuilder line=new StringBuilder();
        try{
            while(true){
                AdbShellPacket packet=stream.read();
                if(packet instanceof AdbShellPacket.Exit)break;
                if(!(packet instanceof AdbShellPacket.StdOut))continue;
                for(byte b:packet.getPayload()){
                    if(b=='\n'){lines.offer(line.toString());line.setLength(0);}
                    else if(line.length()<256)line.append((char)(b&0x7f));
                }
            }
        }catch(Exception ignored){}
        lines.offer("exit");
    }

    private boolean injectorCommand(String command,long timeoutMs){
        try{
            injectorReplies.clear();
            injector.write(command+"\n");
            return "ok".equals(injectorReplies.poll(timeoutMs,TimeUnit.MILLISECONDS));
        }catch(InterruptedException e){Thread.currentThread().interrupt();return false;}
        catch(Exception e){return false;}
    }

    private void stopInjector(){
        if(injector!=null){try{injector.write("q\n");}catch(Exception ignored){}try{injector.close();}catch(Exception ignored){}}
        injector=null;injectorReplies=null;
    }

    static boolean allowedKey(int code){return KeyInjector.allowedKey(code);}

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
            if(!own.equals(restore))writeJournal(restore);
            check(adb.shell("ime enable '"+own+"'"));
            check(adb.shell("ime set '"+own+"'"));switched=true;
            if(!delivery.done.await(4,TimeUnit.SECONDS)||!delivery.success)throw new IOException("Select a text field on the TV, then send your text again.");
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Text input was interrupted.",e);}
        finally{
            GlassRemoteInputMethod.cancel(delivery);
            if(!own.equals(restore)){
                if(switched){
                    try{check(adb.shell("ime set '"+restore+"'"));clearJournal();}catch(Exception e){throw new IOException("Your usual TV keyboard could not be restored. Select it in TV keyboard settings.",e);}
                }else clearJournal();
            }
        }
    }

    /** If Glass died between selecting its keyboard and restoring the user's,
     *  the TV is left with an invisible keyboard. Repair that, but only while
     *  Glass's keyboard is still the selected one: a later choice by the user
     *  always wins. */
    private void recoverKeyboard(){
        File journal=new File(context.getNoBackupFilesDir(),IME_JOURNAL);
        if(!journal.exists())return;
        try{
            String restore=new String(Files.readAllBytes(journal.toPath()),StandardCharsets.UTF_8).trim();
            String current=Settings.Secure.getString(context.getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD);
            ComponentName cur=ComponentName.unflattenFromString(current==null?"":current);
            String own=new ComponentName(context,GlassRemoteInputMethod.class).flattenToShortString();
            if(cur!=null&&own.equals(cur.flattenToShortString())&&restore.matches("[A-Za-z0-9._]+/[A-Za-z0-9._$]+")){
                Dadb d;synchronized(this){d=adb;}
                if(d!=null){check(d.shell("ime set '"+restore+"'"));Log.i(TAG,"Restored the TV keyboard after an interrupted send");}
            }
            clearJournal();
        }catch(Exception e){Log.w(TAG,"Keyboard recovery failed",e);}
    }

    private void writeJournal(String restore)throws IOException{
        try(FileOutputStream out=new FileOutputStream(new File(context.getNoBackupFilesDir(),IME_JOURNAL))){
            out.write(restore.getBytes(StandardCharsets.UTF_8));out.getFD().sync();
        }
    }

    private void clearJournal(){new File(context.getNoBackupFilesDir(),IME_JOURNAL).delete();}

    private static void check(AdbShellResponse response)throws IOException{
        if(response.getExitCode()!=0)throw new IOException("The TV could not complete that action.");
    }
    @Override public synchronized void close(){connected=false;stopInjector();if(adb!=null)try{adb.close();}catch(Exception ignored){}adb=null;}
}
