package tv.glass.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
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
        fill(content,null,false);
        return content;
    }
    /** (Re)build the page in place, keeping focus on {@code focusTag}. */
    private static void fill(LinearLayout content,String focusTag,boolean confirmForget){
        Context context=content.getContext();
        content.removeAllViews();
        boolean on=GlassRemoteAccess.enabled(context);
        try{
            if(on){
                GlassRemoteAccess access=GlassRemoteAccess.load(context);String address=address();
                TextView intro=text(context,"Scan with your camera",16);intro.setGravity(Gravity.CENTER);content.addView(intro);
                if(address!=null){
                    String url="http://"+address+":"+GlassRemoteServer.PORT+"/";String pairUrl=url+"?pair="+access.pin;
                    BitMatrix matrix=new QRCodeWriter().encode(pairUrl,BarcodeFormat.QR_CODE,256,256,Collections.singletonMap(EncodeHintType.MARGIN,2));
                    int[] pixels=new int[256*256];for(int y=0;y<256;y++)for(int x=0;x<256;x++)pixels[y*256+x]=matrix.get(x,y)?Color.BLACK:Color.WHITE;
                    Bitmap image=Bitmap.createBitmap(pixels,256,256,Bitmap.Config.ARGB_8888);
                    ImageView qr=new ImageView(context);qr.setImageBitmap(image);qr.setContentDescription("QR code for the phone remote");
                    LinearLayout.LayoutParams qp=new LinearLayout.LayoutParams(NativeTheme.px(context,200),NativeTheme.px(context,200));qp.topMargin=NativeTheme.px(context,16);qp.bottomMargin=NativeTheme.px(context,10);content.addView(qr,qp);
                    content.addView(text(context,url,15));
                }else content.addView(text(context,"Connect this TV to Wi-Fi first.",15));
                TextView pin=text(context,"Pairing code  ·  "+access.pin,24);LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-2,-2);pp.topMargin=NativeTheme.px(context,14);content.addView(pin,pp);
                TextView hint=text(context,"Scanning connects your phone instantly — no code to type.\nThen add it to your Home Screen when the page asks.",12);hint.setGravity(Gravity.CENTER);hint.setTextColor(0xFFD4CEDA);
                LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);hp.topMargin=NativeTheme.px(context,12);content.addView(hint,hp);
            }else{
                TextView off=text(context,"The phone remote is off. Phones can’t control this TV.",15);off.setGravity(Gravity.CENTER);content.addView(off);
            }
        }catch(Exception e){content.addView(text(context,"Phone remote is starting. Close and reopen this page.",15));}
        View toggle=row(context,"remote-toggle","Phone remote",on?"On · phones on this Wi-Fi can pair":"Off · the TV doesn’t listen for phones",on,()->{
            try{GlassRemoteAccess.setEnabled(context,!on);}catch(Exception ignored){}
            AirPlayMonitorService.start(context,AirPlayMonitorService.ACTION_REMOTE_CHANGED);
            fill(content,"remote-toggle",false);
        });
        addRow(content,toggle,24);
        if(on){
            View forget=row(context,"remote-forget","Forget paired phones",
                    confirmForget?"Press again: every phone must scan the new code":"New code; phones paired before stop working",null,()->{
                if(!confirmForget){fill(content,"remote-forget",true);return;}
                try{GlassRemoteAccess.rotate(context);}catch(Exception ignored){}
                AirPlayMonitorService.start(context,AirPlayMonitorService.ACTION_REMOTE_CHANGED);
                fill(content,"remote-forget",false);
            });
            addRow(content,forget,10);
        }
        if(focusTag!=null){View f=content.findViewWithTag(focusTag);if(f!=null)f.requestFocus();}
    }
    private static void addRow(LinearLayout content,View row,int top){
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,NativeTheme.px(content.getContext(),64));lp.topMargin=NativeTheme.px(content.getContext(),top);
        content.addView(row,lp);
    }
    /** Glass row matching Control Center; {@code checked} non-null draws a switch. */
    private static View row(Context c,String tag,String title,String subtitle,Boolean checked,Runnable action){
        LinearLayout row=new LinearLayout(c);row.setGravity(Gravity.CENTER_VERTICAL);
        int pad=NativeTheme.px(c,16);row.setPadding(pad,NativeTheme.px(c,8),pad,NativeTheme.px(c,8));
        row.setFocusable(true);row.setFocusableInTouchMode(true);row.setClickable(true);row.setTag(tag);
        row.setContentDescription(title+", "+subtitle);
        LinearLayout words=new LinearLayout(c);words.setOrientation(LinearLayout.VERTICAL);
        row.addView(words,new LinearLayout.LayoutParams(0,-2,1));
        TextView t=text(c,title,16);t.setTag("primary");words.addView(t);
        TextView s=text(c,subtitle,12);s.setTag("secondary");s.setTextColor(0xFFD4CEDA);s.setSingleLine(true);s.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams sl=new LinearLayout.LayoutParams(-1,-2);sl.topMargin=NativeTheme.px(c,4);words.addView(s,sl);
        if(checked!=null){
            row.addView(NativeTheme.toggle(c,checked),new LinearLayout.LayoutParams(NativeTheme.px(c,42),NativeTheme.px(c,24)));
            NativeTheme.switchSemantics(row,checked);
        }
        row.setBackground(NativeTheme.row(c,false,18));
        row.setOnClickListener(v->action.run());
        row.setOnFocusChangeListener((v,f)->{row.setBackground(NativeTheme.row(c,f,18));ControlCenterPanel.tint(row,f);});
        return row;
    }
    private static TextView text(Context c,String value,int size){TextView v=new TextView(c);v.setText(value);v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,NativeTheme.textPx(c,size));v.setTypeface(NativeTheme.regular());v.setTextColor(0xFFF8F7FA);return v;}
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
