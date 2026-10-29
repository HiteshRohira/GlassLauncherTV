package tv.glass.launcher;

import android.content.Context;
import android.net.wifi.*;
import android.os.*;
import org.json.*;
import java.io.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;

/** Fixed, one-request shell-UID bridge. Requests (including passwords) use stdin, never shell text. */
public final class WifiControlBridge {
    public static void main(String[] args) {
        Thread watchdog=new Thread(()->{try{Thread.sleep(14000);}catch(InterruptedException ignored){}Runtime.getRuntime().halt(124);},"wifi-deadline");watchdog.setDaemon(true);watchdog.start();
        Looper.prepareMainLooper();
        final WifiManager manager;
        try {
            Class<?> at=Class.forName("android.app.ActivityThread");Object thread=at.getMethod("systemMain").invoke(null);
            Context context=((Context)at.getMethod("getSystemContext").invoke(thread)).createPackageContext("com.android.shell",0);
            WifiManager base=(WifiManager)context.getSystemService(Context.WIFI_SERVICE);
            java.lang.reflect.Field field=WifiManager.class.getDeclaredField("mService");field.setAccessible(true);Object service=field.get(base);
            Context shellContext=new android.content.ContextWrapper(context){
                @Override public String getOpPackageName(){return "com.android.shell";}
                @Override public String getPackageName(){return "com.android.shell";}
            };
            manager=(WifiManager)WifiManager.class.getConstructor(Context.class,Class.forName("android.net.wifi.IWifiManager"),Looper.class).newInstance(shellContext,service,Looper.getMainLooper());
        } catch(Throwable error) { System.out.println("MIRAGE_WIFI {\"ok\":false}");Runtime.getRuntime().halt(1);return; }
        new Thread(()->{
            JSONObject result;
            try {
                StringBuilder line=new StringBuilder();InputStreamReader input=new InputStreamReader(System.in,java.nio.charset.StandardCharsets.UTF_8);
                int ch;while((ch=input.read())!=-1 && ch!='\n'){if(line.length()>=8192)throw new IOException();line.append((char)ch);}
                result=execute(manager,new JSONObject(line.toString()));
                result.put("ok",true);
            } catch(Throwable error) { result=new JSONObject();try{result.put("ok",false).put("error","Wi-Fi control is unavailable or was refused by Android.");}catch(JSONException ignored){} }
            System.out.println("MIRAGE_WIFI "+result);System.out.flush();Runtime.getRuntime().halt(0);
        },"wifi-request").start();
        Looper.loop();
    }
    @SuppressWarnings("deprecation")
    static JSONObject execute(WifiManager wifi,JSONObject request)throws Exception {
        String op=request.getString("op");
        switch(op) {
            case "read":break;
            case "scan":if(!wifi.startScan())throw new IOException();break;
            case "enabled":if(!wifi.setWifiEnabled(request.getBoolean("enabled")))throw new IOException();break;
            case "saved":if(request.getInt("id")<0)throw new IOException();connect(wifi,int.class,request.getInt("id"));break;
            case "join":connect(wifi,WifiConfiguration.class,configuration(request));break;
            default:throw new IOException();
        }
        return snapshot(wifi);
    }
    static WifiConfiguration configuration(JSONObject request)throws Exception {
                String ssid=request.getString("ssid"),security=request.getString("security"),password=request.optString("password");
                if(ssid.isEmpty() || ssid.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>32)throw new IOException();
                WifiConfiguration config=new WifiConfiguration();config.SSID="\""+ssid+"\"";
                if("open".equals(security)) {
                    if(Build.VERSION.SDK_INT>=30)config.setSecurityParams(WifiConfiguration.SECURITY_TYPE_OPEN);
                    else config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                } else if("wpa2".equals(security) || "wpa3".equals(security)) {
                    if(password.length()<8 || password.length()>63)throw new IOException();
                    if("wpa3".equals(security)) {
                        if(Build.VERSION.SDK_INT<30)throw new IOException();
                        config.setSecurityParams(WifiConfiguration.SECURITY_TYPE_SAE);
                    } else if(Build.VERSION.SDK_INT>=30)config.setSecurityParams(WifiConfiguration.SECURITY_TYPE_PSK);
                    else config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
                    config.preSharedKey="\""+password+"\"";
                } else throw new IOException();
                return config;
    }
    private static void connect(WifiManager wifi,Class<?> argument,Object value)throws Exception {
        Class<?> listener=Class.forName("android.net.wifi.WifiManager$ActionListener");
        CountDownLatch done=new CountDownLatch(1);boolean[] success={false};
        Object callback=Proxy.newProxyInstance(listener.getClassLoader(),new Class<?>[]{listener},(proxy,method,args)->{
            if("hashCode".equals(method.getName()))return System.identityHashCode(proxy);
            if("equals".equals(method.getName()))return args!=null && args.length==1 && proxy==args[0];
            if("toString".equals(method.getName()))return "Mirage Wi-Fi callback";
            if("onSuccess".equals(method.getName())){success[0]=true;done.countDown();}
            else if("onFailure".equals(method.getName()))done.countDown();
            return null;
        });
        WifiManager.class.getMethod("connect",argument,listener).invoke(wifi,value,callback);
        if(!done.await(7,TimeUnit.SECONDS) || !success[0])throw new IOException();
    }
    @SuppressWarnings("deprecation")
    private static JSONObject snapshot(WifiManager wifi)throws Exception {
        JSONObject out=new JSONObject().put("enabled",wifi.isWifiEnabled());
        WifiInfo info=wifi.getConnectionInfo();int connected=info==null?-1:info.getNetworkId();
        out.put("currentId",connected).put("ssid",info==null?"":info.getSSID()).put("rssi",info==null?-127:info.getRssi()).put("linkMbps",info==null?0:info.getLinkSpeed());
        JSONArray saved=new JSONArray();List<WifiConfiguration> configs=wifi.getConfiguredNetworks();
        if(configs!=null)for(WifiConfiguration c:configs) saved.put(new JSONObject().put("id",c.networkId).put("ssid",unquote(c.SSID)));
        out.put("saved",saved);JSONArray networks=new JSONArray();List<ScanResult> scans=wifi.getScanResults();
        if(scans!=null)for(ScanResult scan:scans)if(!scan.SSID.isEmpty())networks.put(new JSONObject().put("ssid",scan.SSID).put("capabilities",scan.capabilities).put("rssi",scan.level).put("frequency",scan.frequency).put("seenMicros",scan.timestamp));
        return out.put("networks",networks);
    }
    private static String unquote(String s){return s!=null && s.length()>=2 && s.startsWith("\"") && s.endsWith("\"")?s.substring(1,s.length()-1):s==null?"":s;}
    private WifiControlBridge(){}
}
