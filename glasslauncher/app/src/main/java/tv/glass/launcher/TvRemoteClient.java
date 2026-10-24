package tv.glass.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import dadb.Dadb;
import dadb.AdbShellResponse;
import dadb.AdbShellStream;

/** Authenticated local ADB for fixed keys; text never enters a shell command. */
final class TvRemoteClient implements AutoCloseable {
    private final Context context;
    private final SharedPreferences prefs;
    private Dadb adb;
    /** Persistent shell for low-latency kernel key events, or null. */
    private AdbShellStream fastShell;
    /** Android keycode -> "device linuxCode" for keys the fast path can send. */
    private final Map<Integer,String> fastKeys=new HashMap<>();
    /** Generic.kl codes for the remote's keys. Only devices that use the
     *  generic layout are used, so each Linux code maps to exactly this key. */
    private static final int[][] LINUX_KEYS={{19,103,0},{20,108,0},{21,105,0},{22,106,0},{23,353,0},{66,28,0},
        {4,158,0},{3,172,0},{24,115,0},{25,114,0},{164,113,0},{85,164,0},{89,168,0},{90,208,0},{67,14,0}};
    private static final String[] LINUX_NAMES={"KEY_UP","KEY_DOWN","KEY_LEFT","KEY_RIGHT","KEY_SELECT","KEY_ENTER",
        "KEY_BACK","KEY_HOMEPAGE","KEY_VOLUMEUP","KEY_VOLUMEDOWN","KEY_MUTE","KEY_PLAYPAUSE","KEY_REWIND","KEY_FASTFORWARD","KEY_BACKSPACE"};
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
            try{prepareFastKeys();}catch(Exception e){closeFastShell();fastKeys.clear();}
        }catch(Exception e){close();throw new IOException("TV controls could not connect. Approve Android's authorization on the TV, then retry.",e);}
        finally{pairing=false;}
    }
    synchronized void key(int code)throws IOException{
        if(!connected||adb==null)throw new IOException("Reconnect TV controls first.");
        if(!allowedKey(code))throw new IOException("Unknown button.");
        String fast=fastKeys.get(code);
        if(fast!=null && fastShell!=null){
            try{
                String[] dk=fast.split(" ");String d=dk[0],k=dk[1];
                fastShell.write("sendevent "+d+" 1 "+k+" 1;sendevent "+d+" 0 0 0;sendevent "+d+" 1 "+k+" 0;sendevent "+d+" 0 0 0\n");
                return;
            }catch(IOException e){closeFastShell();fastKeys.clear();}
        }
        try{check(adb.shell("input keyevent "+code));}
        catch(IOException e){close();throw e;}
    }

    /** Find an input device that uses Android's generic key layout and
     *  supports each remote key, then keep one shell open to write kernel
     *  events to it. {@code sendevent} is a native toybox applet, so a press
     *  costs a few milliseconds instead of starting the {@code input} JVM. */
    private void prepareFastKeys()throws IOException{
        fastKeys.clear();
        Map<String,String> layouts=new HashMap<>();
        String path=null;
        for(String line:adb.shell("dumpsys input").getAllOutput().split("\n")){
            String t=line.trim();
            if(t.startsWith("Path: "))path=t.substring(6).trim();
            else if(t.startsWith("KeyLayoutFile: ")&&path!=null){layouts.put(path,t.substring(15).trim());path=null;}
        }
        Map<String,String> caps=new HashMap<>();
        String device=null;StringBuilder keys=new StringBuilder();
        for(String line:adb.shell("getevent -pl").getAllOutput().split("\n")){
            if(line.startsWith("add device")){if(device!=null)caps.put(device,keys.toString());device=line.substring(line.indexOf("/dev/")).trim();keys.setLength(0);}
            else keys.append(' ').append(line.trim()).append(' ');
        }
        if(device!=null)caps.put(device,keys.toString());
        for(int i=0;i<LINUX_KEYS.length;i++){
            String chosen=null;
            for(Map.Entry<String,String> e:caps.entrySet()){
                String kl=layouts.get(e.getKey());
                if(kl==null||!kl.toLowerCase(Locale.ROOT).endsWith("/generic.kl"))continue;
                if(!e.getValue().contains(" "+LINUX_NAMES[i]+" "))continue;
                if(!e.getKey().matches("/dev/input/event[0-9]+"))continue;
                // Prefer a non-Bluetooth device so a remote reconnect cannot move it.
                if(chosen==null||e.getKey().compareTo(chosen)<0)chosen=e.getKey();
            }
            if(chosen!=null)fastKeys.put(LINUX_KEYS[i][0],chosen+" "+LINUX_KEYS[i][1]);
        }
        // Group permissions are not enough: SELinux on some TVs (this TCL
        // included) denies the shell writes to input nodes. Probe each chosen
        // device with a harmless EV_SYN and keep only those that accept it;
        // otherwise every key would vanish silently.
        Map<String,Boolean> writable=new HashMap<>();
        for(String fast:new java.util.ArrayList<>(fastKeys.values())){
            String dev=fast.split(" ")[0];
            if(!writable.containsKey(dev))writable.put(dev,adb.shell("sendevent "+dev+" 0 0 0").getExitCode()==0);
        }
        fastKeys.values().removeIf(v->!Boolean.TRUE.equals(writable.get(v.split(" ")[0])));
        if(fastKeys.isEmpty())return;
        fastShell=adb.openShell("sh");
        final AdbShellStream stream=fastShell;
        Thread drain=new Thread(()->{try{while(true){if(stream.read() instanceof dadb.AdbShellPacket.Exit)break;}}catch(Exception ignored){}},"remote-key-shell");
        drain.setDaemon(true);drain.start();
    }

    private void closeFastShell(){if(fastShell!=null)try{fastShell.close();}catch(Exception ignored){}fastShell=null;}
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
    @Override public synchronized void close(){connected=false;closeFastShell();fastKeys.clear();if(adb!=null)try{adb.close();}catch(Exception ignored){}adb=null;}
}
