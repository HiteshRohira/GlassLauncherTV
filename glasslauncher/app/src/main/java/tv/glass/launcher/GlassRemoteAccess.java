package tv.glass.launcher;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Base64;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/** Private pairing material shared by the launcher UI and monitor process. */
final class GlassRemoteAccess {
    final String pin,token;
    private GlassRemoteAccess(String pin,String token){this.pin=pin;this.token=token;}
    static synchronized GlassRemoteAccess load(Context context)throws Exception{
        try(java.io.RandomAccessFile lockFile=new java.io.RandomAccessFile(new File(context.getNoBackupFilesDir(),"phone-remote-access.lock"),"rw");
            java.nio.channels.FileLock lock=lockFile.getChannel().lock()) {
            return loadLocked(context);
        }
    }
    private static GlassRemoteAccess loadLocked(Context context)throws Exception{
        AtomicFile file=new AtomicFile(new File(context.getNoBackupFilesDir(),"phone-remote-access.json"));
        try{JSONObject json=new JSONObject(new String(file.readFully(),StandardCharsets.UTF_8));
            String pin=json.getString("pin"),token=json.getString("token");
            if(!pin.matches("[0-9]{6}")||token.length()!=43)throw new IOException("Invalid pairing material");
            return new GlassRemoteAccess(pin,token);
        }catch(java.io.FileNotFoundException e){
            SecureRandom random=new SecureRandom();byte[] bytes=new byte[32];random.nextBytes(bytes);
            String pin=String.format(java.util.Locale.US,"%06d",random.nextInt(1000000));
            String token=Base64.encodeToString(bytes,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);
            FileOutputStream out=file.startWrite();try{out.write(new JSONObject().put("pin",pin).put("token",token).toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}catch(Exception failure){file.failWrite(out);throw failure;}
            return new GlassRemoteAccess(pin,token);
        }
    }
}
