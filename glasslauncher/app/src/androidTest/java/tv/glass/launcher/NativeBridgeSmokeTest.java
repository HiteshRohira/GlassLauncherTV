package tv.glass.launcher;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static org.junit.Assert.*;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import io.github.jqssun.airplay.bridge.LogListener;
import io.github.jqssun.airplay.bridge.NativeBridge;
import io.github.jqssun.airplay.bridge.RaopCallbackHandler;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Exercises the real JNI library, crypto initialization and receiver HTTP path.
 * This is a load/lifecycle check, not a claim of decoded media or A/V sync. */
@RunWith(AndroidJUnit4.class)
public class NativeBridgeSmokeTest {
    @Test public void everyJniEntryPoint_loads_andReceiverRestartsWithAndWithoutPin() throws Exception {
        NativeBridge bridge = NativeBridge.INSTANCE;
        for (boolean pin : new boolean[]{false, true}) {
            File identity = new File(getInstrumentation().getTargetContext().getCacheDir(),
                    "native-qa-" + System.nanoTime() + ".pem");
            long handle = bridge.nativeInit(new Callbacks(), new byte[]{2, 0, 0, 0, 0, 1},
                    "Glass Native QA", identity.getAbsolutePath(), true, pin);
            assertNotEquals("receiver created", 0, handle);
            try {
                bridge.nativeSetLogLevel(handle, 3);
                bridge.nativeSetDisplaySize(handle, 1280, 720, 60);
                bridge.nativeSetPlist(handle, "overscanned", 0);
                bridge.nativeSetH265Enabled(handle, false);
                bridge.nativeSetCodecs(handle, false, true);
                bridge.nativeSetHlsEnabled(handle, true);
                bridge.nativeSetLang(handle, "en");
                bridge.nativeSetFeature(handle, NativeBridge.FT_MIRROR, true);
                bridge.nativeUpdatePlaybackInfo(handle, 0, 0, 0, false, true);
                assertNotNull(bridge.nativeGetRaopTxtRecords(handle));
                assertNotNull(bridge.nativeGetAirplayTxtRecords(handle));
                assertTrue(bridge.nativeGetServerName(handle).contains("Glass Native QA"));
                assertNotNull(bridge.nativeGetRaopServiceName(handle));
                bridge.nativeSetDefaultStreamValues(48000, 192);
                assertTrue(bridge.nativeServerAudioConfigure(handle, 80, 95, 0,
                        false, false, true, true, false));
                bridge.nativeServerAudioSetClientGain(handle, 0.5f);
                assertFalse("heap buffers are rejected safely",
                        bridge.nativeServerAudioDebug(handle, ByteBuffer.allocate(4096)));
                assertTrue("direct debug buffer can be populated",
                        bridge.nativeServerAudioDebug(handle, ByteBuffer.allocateDirect(4096)));
                bridge.nativeServerAudioFormat(handle, 4, 1024);
                assertTrue("emulator audio stream opens", bridge.nativeServerAudioStart(handle));
                bridge.nativeServerAudioStop(handle);
                // Exercise software codec initialization without claiming packet
                // decoding coverage. Both AAC and ALAC remain present.
                assertTrue(bridge.nativeServerAudioConfigure(handle, 80, 95, 0,
                        true, true, true, true, false));
                bridge.nativeServerAudioFormat(handle, 2, 352);
                assertTrue(bridge.nativeServerAudioStart(handle));
                bridge.nativeServerAudioStop(handle);
                for (int restart = 0; restart < 2; restart++) {
                    int port;
                    try (ServerSocket reserve = new ServerSocket(0)) { port = reserve.getLocalPort(); }
                    assertEquals(port, bridge.nativeStart(handle, port));
                    assertInfoResponse(port);
                    assertStatus(port,"POST /rate?value HTTP/1.1\r\nX-Apple-Session-ID: audit-qa\r\nContent-Length: 0\r\n\r\n",pin?403:400);
                    assertPinProofRequired(port);
                    assertMalformedPin(port,"YnBsaXN0MDDSAQIDBFdhdXRoVGFnU2Vwa08QEAAAAAAAAAAAAAAAAAAAAABPECAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgNFRksAAAAAAAAAQEAAAAAAAAABQAAAAAAAAAAAAAAAAAAAE8=");
                    assertMalformedPin(port,"YnBsaXN0MDDSAQIDA1dhdXRoVGFnU2Vwa0EACA0VGQAAAAAAAAEBAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAb");
                    if(pin)assertStatus(port,"SETUP * RTSP/1.0\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n",403);
                    bridge.nativeStop(handle);
                }
                assertTrue("persistent identity was written", identity.length() > 0);
            } finally {
                bridge.nativeStop(handle);
                bridge.nativeDestroy(handle);
                // Only this test's fresh emulator key, never an app/user identity.
                identity.delete();
            }
        }
    }

    private static void assertPinProofRequired(int port)throws Exception {
        try(Socket socket=new Socket("127.0.0.1",port)){
            socket.setSoTimeout(5000);
            assertEquals(200,pinStep(socket,"YnBsaXN0MDDSAQIDBFZtZXRob2RUdXNlclNwaW5YYXVkaXQtcWEIDRQZHQAAAAAAAAEBAAAAAAAAAAUAAAAAAAAAAAAAAAAAAAAm"));
            assertEquals(470,pinStep(socket,"YnBsaXN0MDDSAQIDBFdhdXRoVGFnU2Vwa08QEPH9M2wegmi20EVHoQVrvNdPECCfKtEsBysBqeSAttxptqxDvKqNBXrIkwi+zyQTBYmVhggNFRksAAAAAAAAAQEAAAAAAAAABQAAAAAAAAAAAAAAAAAAAE8="));
        }
    }
    private static int pinStep(Socket socket,String encoded)throws Exception {
        byte[] body=android.util.Base64.decode(encoded,android.util.Base64.DEFAULT);
        socket.getOutputStream().write(("POST /pair-setup-pin RTSP/1.0\r\nCSeq: 1\r\nContent-Type: application/x-apple-binary-plist\r\nContent-Length: "+body.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().write(body);
        java.io.InputStream in=socket.getInputStream();
        ByteArrayOutputStream headers=new ByteArrayOutputStream();
        int state=0;
        while(headers.size()<8192&&state<4){
            int b=in.read();assertTrue(b>=0);headers.write(b);
            state=(state==0||state==2)?(b=='\r'?state+1:0):(b=='\n'?state+1:0);
        }
        String text=new String(headers.toByteArray(),StandardCharsets.US_ASCII);
        int length=0;
        for(String line:text.split("\r\n"))if(line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:"))length=Integer.parseInt(line.substring(line.indexOf(':')+1).trim());
        byte[] discard=new byte[1024];
        while(length>0){int n=in.read(discard,0,Math.min(length,discard.length));assertTrue(n>0);length-=n;}
        return Integer.parseInt(text.split(" ")[1]);
    }

    private static void assertMalformedPin(int port,String encoded)throws Exception {
        byte[] body=android.util.Base64.decode(encoded,android.util.Base64.DEFAULT);
        try(Socket socket=new Socket("127.0.0.1",port)){
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(("POST /pair-setup-pin RTSP/1.0\r\nCSeq: 1\r\nContent-Type: application/x-apple-binary-plist\r\nContent-Length: "+body.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(body);
            String line=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII)).readLine();
            assertNotNull(line);assertTrue(line,line.contains(" 470 "));
        }
    }

    private static void assertStatus(int port,String request,int status)throws Exception {
        try(Socket socket=new Socket("127.0.0.1",port)){
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            String line=new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII)).readLine();
            assertNotNull(line);assertTrue(line,line.contains(" "+status+" "));
        }
    }

    private static void assertInfoResponse(int port) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(("GET /info RTSP/1.0\r\nCSeq: 1\r\n"
                    + "Content-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            while (!header.toString("US-ASCII").endsWith("\r\n\r\n")) {
                int value = socket.getInputStream().read();
                assertTrue("HTTP header received", value >= 0);
                header.write(value);
                assertTrue("bounded response header", header.size() < 16384);
            }
            assertTrue(header.toString("US-ASCII").startsWith("RTSP/1.0 200"));
            byte[] magic = new byte[8];
            new java.io.DataInputStream(socket.getInputStream()).readFully(magic);
            assertEquals("bplist00", new String(magic, StandardCharsets.US_ASCII));
        }
    }

    private static final class Callbacks implements RaopCallbackHandler, LogListener {
        public byte[] obtainVideoBuffer(int size) { return new byte[size]; }
        public void onVideoData(byte[] d, int n, long t, boolean h) {}
        public void onAudioFormat(int ct, int spf, boolean screen) {}
        public void onVideoSize(float a, float b, float c, float d) {}
        public void onClientVolume(float db) {}
        public float onClientVolumeQuery() { return 0; }
        public void onAudioTeardown() {}
        public void onConnectionInit() {}
        public void onConnectionDestroy() {}
        public void onConnectionReset(int reason) {}
        public void onDisplayPin(String pin) {}
        public void onMetadata(byte[] data) {}
        public void onCoverArt(byte[] data) {}
        public void onProgress(long start, long curr, long end) {}
        public void onDacpId(String id, String remote) {}
        public void onMirrorRunning(boolean running) {}
        public void onVideoPlay(String location, float start) {}
        public void onVideoScrub(float seconds) {}
        public void onVideoRate(float rate) {}
        public void onVideoStop() {}
        public void onVideoSessionPoll() {}
        public void onLog(String message) {}
    }
}
