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
    private final Runnable onLockout;
    private final ThreadPoolExecutor requests=new ThreadPoolExecutor(2,4,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16));
    private final ThreadPoolExecutor nativeWork=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(3));
    /** Keys and text run here, one at a time and in arrival order, so request
     *  threads stay free and two phones cannot reorder each other's presses. */
    private final ThreadPoolExecutor commands=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(COMMAND_QUEUE));
    private final Map<String,Integer> peers=new ConcurrentHashMap<>();
    private static final int COMMAND_QUEUE=24;
    /** Whole request (line, headers, body) must arrive within this, not just each read. */
    private static final long REQUEST_DEADLINE_MS=3_000;
    private static final int MAX_CONNECTIONS_PER_PEER=6;
    private static final long TEXT_WAIT_MS=15_000;
    private final PairGuard guard=new PairGuard(SystemClock::elapsedRealtime);
    private final Map<String,long[]> rates=new ConcurrentHashMap<>();
    private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private volatile String operationError="";
    private ServerSocket server;
    private static final Map<String,Integer> KEYS;
    static{Map<String,Integer> keys=new HashMap<>();keys.put("up",19);keys.put("down",20);keys.put("left",21);keys.put("right",22);keys.put("ok",23);keys.put("back",4);keys.put("home",3);keys.put("playPause",85);keys.put("volumeUp",24);keys.put("volumeDown",25);keys.put("mute",164);keys.put("rewind",89);keys.put("forward",90);keys.put("delete",67);keys.put("enter",66);KEYS=Collections.unmodifiableMap(keys);}
    /** @param onLockout runs (on a request thread) when repeated wrong codes close pairing. */
    GlassRemoteServer(Context context,Runnable onLockout)throws Exception{this.context=context.getApplicationContext();this.onLockout=onLockout;GlassRemoteAccess.load(context);tv=new TvRemoteClient(context);}
    /** Current authoritative pairing material, including cross-process revocation.
     *  The brute-force guard is never reset here: the file also changes on
     *  every pairing, and a reset would hand a guesser a fresh budget. */
    private PairingBook access()throws Exception{
        // Revocation is authoritative under the cross-process lock, not a timestamp cache.
        return GlassRemoteAccess.load(context);
    }
    /** Pairs a phone if {@code pin} is the current code, under the shared
     *  brute-force budget. Returns {device token, install token} or null.
     *  The code is re-checked inside the file lock, so two phones racing with
     *  the same code cannot both use it; on success the TV shows a new code. */
    private String[] pair(String pin,String ip)throws Exception{
        if(pin==null||!pin.matches("[0-9]{6}")||guard.blocked(ip))return null;
        long now=System.currentTimeMillis();
        String[] paired=GlassRemoteAccess.update(context,book->constant(pin,book.pin)
                ?new String[]{book.pair(GlassRemoteAccess.RANDOM,now),book.newInstall(GlassRemoteAccess.RANDOM,now)}:null);
        if(paired!=null){guard.succeed(ip);return paired;}
        if(guard.fail(ip,pin)){Log.w("GlassRemote","Pairing locked after repeated wrong codes");if(onLockout!=null)onLockout.run();}
        return null;
    }
    private boolean installValid(String token)throws Exception{return token!=null&&token.length()==43&&access().installValid(token,System.currentTimeMillis());}
    private static final String COOKIE="; Path=/; HttpOnly; SameSite=Strict; Max-Age=31536000\r\n";
    void start()throws IOException{
        server=new ServerSocket();server.setReuseAddress(true);server.bind(new InetSocketAddress(PORT),16);
        Thread accept=new Thread(()->{
            while(!closed)try{Socket socket=server.accept();socket.setSoTimeout(5000);
                String peer=socket.getInetAddress().getHostAddress();
                if(peers.merge(peer,1,Integer::sum)>MAX_CONNECTIONS_PER_PEER){release(peer);socket.close();continue;}
                sockets.add(socket);
                try{requests.execute(()->serve(socket));}catch(RejectedExecutionException full){sockets.remove(socket);release(peer);socket.close();}
            }catch(IOException error){if(!closed)Log.w("GlassRemote","Remote listener stopped");}
        },"glass-web-remote");accept.setDaemon(true);accept.start();
        if(tv.isPaired())runNative(tv::connect);
    }
    private static boolean limited(Map<String,long[]> map,String ip,int limit,long window){
        synchronized(map){long now=SystemClock.elapsedRealtime();if(map.size()>128)map.entrySet().removeIf(e->now-e.getValue()[0]>window);
            if(map.size()>256&&!map.containsKey(ip))return true;
            long[] entry=map.computeIfAbsent(ip,k->new long[]{now,0});if(now-entry[0]>window){entry[0]=now;entry[1]=0;}return ++entry[1]>limit;
        }
    }
    private void release(String peer){peers.computeIfPresent(peer,(k,n)->n>1?n-1:null);}
    private void serve(Socket socket){
        try{
            InputStream in=new BufferedInputStream(new DeadlineInputStream(socket,SystemClock.elapsedRealtime()+REQUEST_DEADLINE_MS));OutputStream out=socket.getOutputStream();
            String line=readLine(in,2048);String[] request=line.split(" ");
            if(request.length!=3){reply(out,400,"text/plain","Bad request");return;}
            String method=request[0],target=request[1];int q=target.indexOf('?');String path=q<0?target:target.substring(0,q),query=q<0?"":target.substring(q+1);Map<String,String> headers=new HashMap<>();int total=0;
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
                if(guard.lockedOut()){reply(out,429,"application/json","{\"error\":\"Pairing is paused after too many wrong codes. Try again in 15 minutes.\"}");return;}
                if(guard.blocked(ip)){reply(out,429,"application/json","{\"error\":\"Wait a minute before trying another code.\"}");return;}
                JSONObject data=new JSONObject(new String(body,StandardCharsets.UTF_8));
                String[] paired=pair(data.optString("pin"),ip);
                if(paired==null){reply(out,401,"application/json","{\"error\":\"That code does not match the code on the TV.\"}");return;}
                reply(out,200,"application/json",new JSONObject().put("ok",true).put("install",paired[1]).toString(),"Set-Cookie: glass_remote="+paired[0]+COOKIE);return;
            }
            // A Home Screen web app has its own cookie jar: it trades the
            // one-time install token from its start URL for its own token.
            if(method.equals("POST")&&path.equals("/api/install")){
                if(origin==null || !headers.getOrDefault("content-type","").startsWith("application/json")){reply(out,403,"text/plain","Open the remote page to pair.");return;}
                String token=new JSONObject(new String(body,StandardCharsets.UTF_8)).optString("token");
                long now=System.currentTimeMillis();
                String device=token.length()==43?GlassRemoteAccess.update(context,book->book.redeem(token,GlassRemoteAccess.RANDOM,now)):null;
                if(device==null){reply(out,401,"application/json","{\"error\":\"This link was already used or has expired. Enter the code on the TV.\"}");return;}
                reply(out,200,"application/json","{\"ok\":true}","Set-Cookie: glass_remote="+device+COOKIE);return;
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
                if(path.equals("/api/native/start")||path.equals("/api/reconnect")){
                    if(!runNative(path.endsWith("start")?tv::startPairing:tv::connect)){reply(out,503,"application/json","{\"error\":\"The TV is busy. Try again shortly.\"}");return;}
                }
                else if(path.equals("/api/key")){
                    Integer code=KEYS.get(data.optString("key"));if(code==null){reply(out,400,"text/plain","Unknown button");return;}
                    boolean hold=data.optBoolean("long")&&(code==23||code==66);
                    // Accepted is not done: the reply goes out at once and the
                    // key follows in order; failures show up in /api/status.
                    if(!runCommand(()->{if(hold)tv.longPress(code);else tv.key(code);})){reply(out,503,"application/json","{\"error\":\"The TV is busy. Try again shortly.\"}");return;}
                    reply(out,202,"application/json","{\"ok\":true}");return;
                }
                else if(path.equals("/api/text")){
                    String text=data.optString("text");
                    java.util.concurrent.atomic.AtomicInteger deliveryState=new java.util.concurrent.atomic.AtomicInteger();
                    Future<?> done;
                    try{done=commands.submit(()->{if(deliveryState.compareAndSet(0,1))tv.sendText(text);return null;});}
                    catch(RejectedExecutionException busy){reply(out,503,"application/json","{\"error\":\"The TV is busy. Try again shortly.\"}");return;}
                    try{done.get(TEXT_WAIT_MS,TimeUnit.MILLISECONDS);}
                    catch(TimeoutException slow){
                        if(deliveryState.compareAndSet(0,2)){ done.cancel(false);
                            reply(out,504,"application/json","{\"error\":\"Text was cancelled before delivery.\"}"); }
                        else reply(out,202,"application/json","{\"ok\":true,\"pending\":true,\"message\":\"Text is still being delivered. Avoid sending it twice.\"}");
                        return;
                    }
                    catch(ExecutionException failed){Throwable c=failed.getCause();throw c instanceof IOException?(IOException)c:new IOException("Text could not be sent.");}
                }
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
            if(file.equals("manifest.webmanifest")){
                // A Home Screen web app has its own cookie jar on iOS, so the
                // installed icon carries this phone's one-time install token
                // in its start URL and redeems it on first launch. The pairing
                // code itself changes after every pairing and is never put in
                // a URL. Without a valid token, start_url is omitted.
                String install=queryValue(query,"install");
                JSONObject manifest=new JSONObject().put("id","/").put("name","Mirage Remote").put("short_name","Mirage Remote");
                if(installValid(install))manifest.put("start_url","/?install="+install);
                manifest.put("scope","/").put("display","standalone").put("orientation","portrait")
                    .put("background_color","#121319").put("theme_color","#121319")
                    .put("icons",new org.json.JSONArray().put(new JSONObject().put("src","/icon.png").put("sizes","180x180").put("type","image/png")));
                reply(out,200,type,manifest.toString().getBytes(StandardCharsets.UTF_8),"");return;
            }
            try(InputStream asset=context.getAssets().open("remote/"+file)){
                ByteArrayOutputStream data=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=asset.read(buffer))!=-1)data.write(buffer,0,n);
                byte[] bytes=data.toByteArray();
                // iOS reads the manifest link once, as the page loads; put the
                // install token in it server-side so Add to Home Screen keeps it.
                String install=queryValue(query,"install");
                if(file.equals("index.html")&&installValid(install))
                    bytes=new String(bytes,StandardCharsets.UTF_8).replace("href=\"/manifest.webmanifest\"","href=\"/manifest.webmanifest?install="+install+"\"").getBytes(StandardCharsets.UTF_8);
                reply(out,200,type,bytes,"");
            }
        }catch(Exception error){try{reply(socket.getOutputStream(),409,"application/json",new JSONObject().put("error",error instanceof IOException?error.getMessage():"Request could not be completed.").toString());}catch(Exception ignored){}
        }finally{sockets.remove(socket);release(socket.getInetAddress().getHostAddress());try{socket.close();}catch(IOException ignored){}}
    }
    private static String queryValue(String query,String key){
        for(String part:query.split("&")){int eq=part.indexOf('=');if(eq>0&&part.substring(0,eq).equals(key))return part.substring(eq+1);}
        return null;
    }
    private boolean hasCookie(String cookies)throws Exception{PairingBook book=access();for(String cookie:cookies.split(";")){String value=cookie.trim();if(value.startsWith("glass_remote=")&&book.isDevice(value.substring(13)))return true;}return false;}
    private static boolean constant(String a,String b){return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),b.getBytes(StandardCharsets.UTF_8));}
    private static boolean allowedHost(String host){
        if(!host.endsWith(":"+PORT))return false;String name=host.substring(0,host.length()-(":"+PORT).length());
        if(name.startsWith("[")&&name.endsWith("]"))name=name.substring(1,name.length()-1);
        if(name.equals("::1")||name.equals("127.0.0.1")||name.equals("localhost")||name.equals("10.0.2.2"))return true;
        String numeric=name;
        int scope=numeric.indexOf('%');if(scope>=0)numeric=numeric.substring(0,scope);
        if(numeric.indexOf(':')<0&&!numeric.matches("[0-9.]+"))return false;
        if(!numeric.matches("[0-9a-fA-F:.]+"))return false;
        try{
            byte[] requested=InetAddress.getByName(numeric).getAddress();
            Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();
            while(interfaces.hasMoreElements()){
                Enumeration<InetAddress> addresses=interfaces.nextElement().getInetAddresses();
                while(addresses.hasMoreElements())if(Arrays.equals(requested,addresses.nextElement().getAddress()))return true;
            }
        }catch(Exception ignored){}return false;
    }
    private interface Work{void run()throws Exception;}
    private boolean runCommand(Work work){
        try{commands.execute(()->{try{work.run();operationError="";}catch(Exception e){Log.w("GlassRemote","Key failed",e);operationError=e.getMessage()==null?"The TV could not complete that action.":e.getMessage();}});return true;}
        catch(RejectedExecutionException full){return false;}
    }
    /** Socket input with one deadline for the whole request: a client that
     *  trickles bytes just under the per-read timeout cannot hold a worker. */
    private static final class DeadlineInputStream extends FilterInputStream{
        private final Socket socket;private final long deadline;
        DeadlineInputStream(Socket socket,long deadline)throws IOException{super(socket.getInputStream());this.socket=socket;this.deadline=deadline;}
        private void arm()throws IOException{long left=deadline-SystemClock.elapsedRealtime();if(left<=0)throw new SocketTimeoutException("Request took too long");socket.setSoTimeout((int)Math.min(left,5000));}
        @Override public int read()throws IOException{arm();return super.read();}
        @Override public int read(byte[] b,int off,int len)throws IOException{arm();return super.read(b,off,len);}
    }
    private boolean runNative(Work work){
        try{nativeWork.execute(()->{operationError="";try{work.run();}catch(Exception e){Log.w("GlassRemote","TV control operation failed",e);operationError=e instanceof javax.net.ssl.SSLException || e instanceof java.net.SocketException || e instanceof java.net.SocketTimeoutException || e.getMessage()==null ? "TV controls are unavailable. Try connecting again." : e.getMessage();}});return true;}
        catch(RejectedExecutionException full){return false;}
    }
    private static String readLine(InputStream in,int limit)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();int prev=-1;for(int i=0;i<limit;i++){int b=in.read();if(b<0)throw new EOFException();if(b=='\n'){if(prev!='\r')throw new IOException("Invalid line");byte[] data=out.toByteArray();return new String(data,0,data.length-1,StandardCharsets.US_ASCII);}out.write(b);prev=b;}throw new IOException("Line too long");}
    private static void reply(OutputStream out,int code,String type,String body)throws IOException{reply(out,code,type,body.getBytes(StandardCharsets.UTF_8),"");}
    private static void reply(OutputStream out,int code,String type,String body,String extra)throws IOException{reply(out,code,type,body.getBytes(StandardCharsets.UTF_8),extra);}
    private static void reply(OutputStream out,int code,String type,byte[] body,String extra)throws IOException{
        String csp="default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; manifest-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
        String header="HTTP/1.1 "+code+" "+(code==200?"OK":code==202?"Accepted":"Error")+"\r\nContent-Type: "+type+"\r\nContent-Length: "+body.length+"\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: "+csp+"\r\n"+extra+"\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();
    }
    @Override public void close(){closed=true;if(server!=null)try{server.close();}catch(IOException ignored){}for(Socket s:sockets)try{s.close();}catch(IOException ignored){}requests.shutdownNow();nativeWork.shutdownNow();commands.shutdownNow();tv.close();}
}
