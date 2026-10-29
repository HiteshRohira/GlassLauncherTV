package tv.glass.launcher;

import android.content.Context;
import dadb.*;
import org.json.JSONObject;
import java.io.IOException;

/** One short-lived connection per operation. No persistent input injector or polling process. */
final class WifiControlClient {
    static JSONObject request(Context context,JSONObject request)throws Exception {
        String apk=context.getApplicationInfo().sourceDir;
        if(!apk.matches("/[A-Za-z0-9/._=~+-]+\\.apk"))throw new IOException("Unsupported application path");
        if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException();
        try(Dadb adb=GoogleTvSetup.connect(context)) {
            if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException();
            try(AdbShellStream stream=adb.openShell("CLASSPATH='"+apk+"' exec toybox timeout -s KILL 18 app_process /system/bin "+WifiControlBridge.class.getName())) {
            stream.write(request.toString()+"\n");java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
            while(bytes.size()<262144) {
                AdbShellPacket packet=stream.read();
                if(packet instanceof AdbShellPacket.Exit)break;
                if(packet instanceof AdbShellPacket.StdOut)bytes.write(packet.getPayload());
            }
            String output=bytes.toString("UTF-8");
            int start=output.indexOf("MIRAGE_WIFI ");if(start<0)throw new IOException("Wi-Fi controls did not respond");
            String line=output.substring(start+12).split("\\r?\\n",2)[0];JSONObject result=new JSONObject(line);
            if(!result.optBoolean("ok"))throw new IOException("Android refused this Wi-Fi operation");
            return result;
            }
        }
    }
    private WifiControlClient(){}
}
