package tv.glass.launcher;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Small HTTP-only LAN host. No shell, command strings, remote URLs, or credentials API. */
final class GlassRemoteServer implements AutoCloseable {
    static final int PORT=48633;
    private final Context context;
    private final TvRemoteClient tv;
    private final GlassRemoteAccess access;
    private final ThreadPoolExecutor requests=new ThreadPoolExecutor(2,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16));
    private final ThreadPoolExecutor nativeWork=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(3));
    private final Map<String,long[]> attempts=new ConcurrentHashMap<>();
    private final Map<String,long[]> rates=new ConcurrentHashMap<>();
    private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private volatile String operationError="";
    private ServerSocket server;
    private static final Map<String,Integer> KEYS;
    static{Map<String,Integer> keys=new HashMap<>();keys.put("up",19);keys.put("down",20);keys.put("left",21);keys.put("right",22);keys.put("ok",23);keys.put("back",4);keys.put("home",3);keys.put("playPause",85);keys.put("volumeUp",24);keys.put("volumeDown",25);keys.put("mute",164);keys.put("rewind",89);keys.put("forward",90);keys.put("delete",67);keys.put("enter",66);KEYS=Collections.unmodifiableMap(keys);}
    GlassRemoteServer(Context context)throws Exception{this.context=context.getApplicationContext();access=GlassRemoteAccess.load(context);tv=new TvRemoteClient(context);}
    void start()throws IOException{
        server=new ServerSocket();server.setReuseAddress(true);server.bind(new InetSocketAddress(PORT),16);
        Thread accept=new Thread(()->{
            while(!closed)try{Socket socket=server.accept();socket.setSoTimeout(5000);sockets.add(socket);
                try{requests.execute(()->serve(socket));}catch(RejectedExecutionException full){sockets.remove(socket);socket.close();}
            }catch(IOException error){if(!closed)Log.w("GlassRemote","Remote listener stopped");}
        },"glass-web-remote");accept.setDaemon(true);accept.start();
        if(tv.isPaired())runNative(()->tv.connect());
    }
    private static boolean limited(Map<String,long[]> map,String ip,int limit,long window){
        synchronized(map){long now=SystemClock.elapsedRealtime();if(map.size()>128)map.entrySet().removeIf(e->now-e.getValue()[0]>window);
            if(map.size()>256&&!map.containsKey(ip))return true;
            long[] entry=map.computeIfAbsent(ip,k->new long[]{now,0});if(now-entry[0]>window){entry[0]=now;entry[1]=0;}return ++entry[1]>limit;
        }
    }
    private void serve(Socket socket){
        try{
            InputStream in=new BufferedInputStream(socket.getInputStream());OutputStream out=socket.getOutputStream();
            String line=readLine(in,2048);String[] request=line.split(" ");
            if(request.length!=3){reply(out,400,"text/plain","Bad request");return;}
            String method=request[0],path=request[1];Map<String,String> headers=new HashMap<>();int total=0;
            while(!(line=readLine(in,4096)).isEmpty()){
                total+=line.length();if(total>8192)throw new IOException("Headers too large");int colon=line.indexOf(':');if(colon<1)throw new IOException("Invalid header");
                String name=line.substring(0,colon).toLowerCase(Locale.ROOT);if(headers.put(name,line.substring(colon+1).trim())!=null)throw new IOException("Duplicate header");
            }
            String host=headers.getOrDefault("host","");
            if(!allowedHost(host)){reply(out,403,"text/plain","Use the TV's local address.");return;}
            String origin=headers.get("origin");
            // Cross-origin checks guard the API only. A Home Screen web app
            // launch is a top-level navigation that iOS may label cross-site
            // (or give a "null" origin); the static page itself is harmless.
            if(path.startsWith("/api/")){
                if(origin!=null&&!origin.equals("http://"+host)){reply(out,403,"text/plain","Origin rejected");return;}
                if("cross-site".equals(headers.get("sec-fetch-site"))){reply(out,403,"text/plain","Origin rejected");return;}
            }
            String ip=socket.getInetAddress().getHostAddress();
            if(limited(rates,ip,100,10000)){reply(out,429,"application/json","{\"error\":\"Too many requests. Try again shortly.\"}");return;}
            if(headers.containsKey("transfer-encoding")){reply(out,400,"text/plain","Unsupported request encoding");return;}
            int length=Integer.parseInt(headers.getOrDefault("content-length","0"));
            if(length<0||length>12288){reply(out,413,"text/plain","Request too large");return;}
            if(!method.equals("GET")&&!method.equals("POST")){reply(out,405,"text/plain","Method not allowed");return;}
            byte[] body=new byte[length];int offset=0;while(offset<length){int n=in.read(body,offset,length-offset);if(n<0)throw new EOFException();offset+=n;}
            boolean authenticated=hasCookie(headers.getOrDefault("cookie",""));
            if(method.equals("POST")&&path.equals("/api/pair")){
                if(origin==null || !headers.getOrDefault("content-type","").startsWith("application/json")){reply(out,403,"text/plain","Open the remote page to pair.");return;}
                if(limited(attempts,ip,5,60000)){reply(out,429,"application/json","{\"error\":\"Wait a minute before trying another code.\"}");return;}
                JSONObject data=new JSONObject(new String(body,StandardCharsets.UTF_8));
                if(!constant(data.optString("pin"),access.pin)){reply(out,401,"application/json","{\"error\":\"That code does not match the code in Glass.\"}");return;}
                reply(out,200,"application/json","{\"ok\":true}","Set-Cookie: glass_remote="+access.token+"; Path=/; HttpOnly; SameSite=Strict; Max-Age=31536000\r\n");return;
            }
            if(path.startsWith("/api/")){
                if(!authenticated){reply(out,401,"application/json","{\"authenticated\":false}");return;}
                if(method.equals("GET")&&path.equals("/api/status")){
                    JSONObject status=new JSONObject().put("authenticated",true).put("connected",tv.connected).put("paired",tv.isPaired()).put("pairing",tv.pairing)
                        .put("keyboardReady",tv.keyboardReady()).put("message",operationError.isEmpty()?tv.message:operationError);
                    reply(out,200,"application/json",status.toString());return;
                }
                if(!method.equals("POST")||origin==null||!headers.getOrDefault("content-type","").startsWith("application/json")){reply(out,403,"text/plain","Use the remote page.");return;}
                JSONObject data=new JSONObject(new String(body,StandardCharsets.UTF_8));
                if(path.equals("/api/native/start")){runNative(()->tv.startPairing());}
                else if(path.equals("/api/reconnect")){runNative(()->tv.connect());}
                else if(path.equals("/api/key")){Integer code=KEYS.get(data.optString("key"));if(code==null){reply(out,400,"text/plain","Unknown button");return;}tv.key(code);}
                else if(path.equals("/api/text")){tv.sendText(data.optString("text"));}
                else{reply(out,404,"text/plain","Not found");return;}
                reply(out,200,"application/json","{\"ok\":true}");return;
            }
            if(!method.equals("GET")){reply(out,405,"text/plain","Method not allowed");return;}
            String file,type;
            switch(path){
                case "/":case "/index.html":file="index.html";type="text/html; charset=utf-8";break;
                case "/app.js":file="app.js";type="text/javascript; charset=utf-8";break;
                case "/style.css":file="style.css";type="text/css; charset=utf-8";break;
                case "/manifest.webmanifest":file="manifest.webmanifest";type="application/manifest+json";break;
                case "/icon.png":file="icon.png";type="image/png";break;
                default:reply(out,404,"text/plain","Not found");return;
            }
            try(InputStream asset=context.getAssets().open("remote/"+file)){
                ByteArrayOutputStream data=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=asset.read(buffer))!=-1)data.write(buffer,0,n);
                reply(out,200,type,data.toByteArray(),"");
            }
        }catch(Exception error){try{reply(socket.getOutputStream(),409,"application/json",new JSONObject().put("error",error instanceof IOException?error.getMessage():"Request could not be completed.").toString());}catch(Exception ignored){}
        }finally{sockets.remove(socket);try{socket.close();}catch(IOException ignored){}}
    }
    private boolean hasCookie(String cookies){for(String cookie:cookies.split(";")){String value=cookie.trim();if(value.startsWith("glass_remote=")&&constant(value.substring(13),access.token))return true;}return false;}
    private static boolean constant(String a,String b){return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}
    private static boolean allowedHost(String host){
        if(!host.endsWith(":"+PORT))return false;String name=host.substring(0,host.length()-(":"+PORT).length());
        if(name.equals("127.0.0.1")||name.equals("localhost")||name.equals("10.0.2.2"))return true;
        try{Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();while(interfaces.hasMoreElements()){Enumeration<InetAddress> addresses=interfaces.nextElement().getInetAddresses();while(addresses.hasMoreElements())if(name.equals(addresses.nextElement().getHostAddress()))return true;}}catch(Exception ignored){}return false;
    }
    private interface Work{void run()throws Exception;}
    private void runNative(Work work){if(nativeWork.getQueue().size()>2)return;nativeWork.execute(()->{operationError="";try{work.run();}catch(Exception e){Log.w("GlassRemote","TV control operation failed",e);operationError=e instanceof javax.net.ssl.SSLException || e instanceof java.net.SocketException || e instanceof java.net.SocketTimeoutException || e.getMessage()==null ? "TV controls are unavailable. Try connecting again." : e.getMessage();}});}
    private static String readLine(InputStream in,int limit)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();int prev=-1;for(int i=0;i<limit;i++){int b=in.read();if(b<0)throw new EOFException();if(b=='\n'){if(prev!='\r')throw new IOException("Invalid line");byte[] data=out.toByteArray();return new String(data,0,data.length-1,StandardCharsets.US_ASCII);}out.write(b);prev=b;}throw new IOException("Line too long");}
    private static void reply(OutputStream out,int code,String type,String body)throws IOException{reply(out,code,type,body.getBytes(StandardCharsets.UTF_8),"");}
    private static void reply(OutputStream out,int code,String type,String body,String extra)throws IOException{reply(out,code,type,body.getBytes(StandardCharsets.UTF_8),extra);}
    private static void reply(OutputStream out,int code,String type,byte[] body,String extra)throws IOException{
        String csp="default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; manifest-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
        String header="HTTP/1.1 "+code+" "+(code==200?"OK":"Error")+"\r\nContent-Type: "+type+"\r\nContent-Length: "+body.length+"\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: "+csp+"\r\n"+extra+"\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
    }
    @Override public void close(){closed=true;if(server!=null)try{server.close();}catch(IOException ignored){}for(Socket s:sockets)try{s.close();}catch(IOException ignored){}requests.shutdownNow();nativeWork.shutdownNow();tv.close();}
}
