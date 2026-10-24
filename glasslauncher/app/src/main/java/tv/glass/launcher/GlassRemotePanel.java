package tv.glass.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;

/** Phone setup remains inside the same Control Center surface. */
final class GlassRemotePanel {
    static LinearLayout create(Context context){
        LinearLayout content=new LinearLayout(context);content.setOrientation(LinearLayout.VERTICAL);content.setGravity(Gravity.CENTER_HORIZONTAL);
        try{
            GlassRemoteAccess access=GlassRemoteAccess.load(context);String address=address();
            TextView intro=text(context,"Scan with your iPhone camera",16);intro.setGravity(Gravity.CENTER);content.addView(intro);
            if(address!=null){
                String url="http://"+address+":"+GlassRemoteServer.PORT+"/";
                BitMatrix matrix=new QRCodeWriter().encode(url,BarcodeFormat.QR_CODE,256,256,Collections.singletonMap(EncodeHintType.MARGIN,2));
                int[] pixels=new int[256*256];for(int y=0;y<256;y++)for(int x=0;x<256;x++)pixels[y*256+x]=matrix.get(x,y)?Color.BLACK:Color.WHITE;
                Bitmap image=Bitmap.createBitmap(pixels,256,256,Bitmap.Config.ARGB_8888);
                ImageView qr=new ImageView(context);qr.setImageBitmap(image);qr.setContentDescription("QR code for the phone remote");
                LinearLayout.LayoutParams qp=new LinearLayout.LayoutParams(NativeTheme.px(context,150),NativeTheme.px(context,150));qp.topMargin=NativeTheme.px(context,12);qp.bottomMargin=NativeTheme.px(context,10);content.addView(qr,qp);
                content.addView(text(context,url,15));
            }else content.addView(text(context,"Connect this TV to Wi-Fi first.",15));
            TextView pin=text(context,"Pairing code  ·  "+access.pin,24);LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-2,-2);pp.topMargin=NativeTheme.px(context,14);content.addView(pin,pp);
            TextView hint=text(context,"Use the same Wi-Fi. In Safari, tap Share → Add to Home Screen.\nConnect TV controls once from the webpage.",12);hint.setGravity(Gravity.CENTER);hint.setTextColor(0xFFD4CEDA);
            LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);hp.topMargin=NativeTheme.px(context,12);content.addView(hint,hp);
        }catch(Exception e){content.addView(text(context,"Phone remote is starting. Close and reopen this page.",15));}
        return content;
    }
    private static TextView text(Context c,String value,int size){TextView v=new TextView(c);v.setText(value);v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,NativeTheme.px(c,size));v.setTypeface(NativeTheme.regular());v.setTextColor(0xFFF8F7FA);return v;}
    private static String address()throws Exception{
        Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();String fallback=null;
        while(interfaces.hasMoreElements()){
            NetworkInterface network=interfaces.nextElement();if(!network.isUp()||network.isLoopback())continue;
            Enumeration<InetAddress> addresses=network.getInetAddresses();while(addresses.hasMoreElements()){
                InetAddress a=addresses.nextElement();if(a instanceof Inet4Address && a.isSiteLocalAddress()){
                    if(network.getName().startsWith("wlan")||network.getName().startsWith("eth"))return a.getHostAddress();fallback=a.getHostAddress();
                }
            }
        }return fallback;
    }
    private GlassRemotePanel(){}
}
