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
    /** How long "Forget paired phones" waits for the confirming press. */
    private static final long CONFIRM_MS=5000;
    private static final java.util.concurrent.ThreadPoolExecutor worker =
            new java.util.concurrent.ThreadPoolExecutor(1,1,0,java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.ArrayBlockingQueue<>(4),r->{Thread t=new Thread(r,"mirage-remote-panel");t.setDaemon(true);return t;},
                    new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy());
    private static final java.util.Map<LinearLayout,Long> generations=new java.util.WeakHashMap<>();

    static LinearLayout create(Context context){
        LinearLayout content=new LinearLayout(context);content.setOrientation(LinearLayout.VERTICAL);content.setGravity(Gravity.CENTER_HORIZONTAL);
        fill(content,null,false);
        // A phone that pairs replaces the code: show the new QR and code without
        // having to reopen the page.
        final long[] seen={-1};
        final java.util.concurrent.atomic.AtomicBoolean checking=new java.util.concurrent.atomic.AtomicBoolean();
        Runnable watch=new Runnable(){@Override public void run(){
            if(!content.isAttachedToWindow())return;
            if(checking.compareAndSet(false,true))worker.execute(()->{
                long version=GlassRemoteAccess.version(context);
                content.post(()->{
                    checking.set(false);
                    if(!content.isAttachedToWindow())return;
                    if(!java.util.Objects.equals(content.getTag(),version)){seen[0]=version;View f=content.findFocus();fill(content,f!=null&&f.getTag()!=null?f.getTag().toString():null,false);}
                });
            });
            content.postDelayed(this,1500);
        }};
        content.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){
            @Override public void onViewAttachedToWindow(View v){content.removeCallbacks(watch);content.postDelayed(watch,1500);}
            @Override public void onViewDetachedFromWindow(View v){content.removeCallbacks(watch);generations.remove(content);}
        });
        return content;
    }
    /** (Re)build the page in place, keeping focus on {@code focusTag}. */
    private static void fill(LinearLayout content,String focusTag,boolean confirmForget){
        long generation=generations.getOrDefault(content,0L)+1;
        generations.put(content,generation);
        Context context=content.getContext().getApplicationContext();
        worker.execute(()->{
            boolean enabled=GlassRemoteAccess.enabled(context);
            PairingBook book=null;String host=null;Bitmap qr=null;
            try{
                book=GlassRemoteAccess.load(context);
                if(enabled){
                    host=address();
                    if(host!=null){
                        String url="http://"+host+":"+GlassRemoteServer.PORT+"/?pair="+book.pin;
                        BitMatrix matrix=new QRCodeWriter().encode(url,BarcodeFormat.QR_CODE,256,256,Collections.singletonMap(EncodeHintType.MARGIN,2));
                        int[] pixels=new int[256*256];for(int y=0;y<256;y++)for(int x=0;x<256;x++)pixels[y*256+x]=matrix.get(x,y)?Color.BLACK:Color.WHITE;
                        qr=Bitmap.createBitmap(pixels,256,256,Bitmap.Config.ARGB_8888);
                    }
                }
            }catch(Exception ignored){}
            final PairingBook readyBook=book;final String readyHost=host;final Bitmap readyQr=qr;
            content.post(()->{
                if(!java.util.Objects.equals(generations.get(content),generation)){
                    if(readyQr!=null)readyQr.recycle();return;
                }
                render(content,focusTag,confirmForget,enabled,readyBook,readyHost,readyQr);
            });
        });
    }
    private static void render(LinearLayout content,String focusTag,boolean confirmForget,
                               boolean on,PairingBook access,String address,Bitmap image){
        Context context=content.getContext();
        View currentFocus=content.findFocus();
        if(currentFocus!=null&&currentFocus.getTag() instanceof String)focusTag=(String)currentFocus.getTag();
        content.setTag(access==null?-1L:access.revision);
        content.removeAllViews();
        try{
            if(on){
                if(access==null)throw new IllegalStateException("Remote settings unavailable");
                TextView intro=text(context,"Scan with your camera",16);intro.setGravity(Gravity.CENTER);content.addView(intro);
                if(address!=null){
                    String url="http://"+address+":"+GlassRemoteServer.PORT+"/";String pairUrl=url+"?pair="+access.pin;
                    ImageView qr=new ImageView(context);qr.setImageBitmap(image);qr.setContentDescription("QR code for the phone remote");
                    LinearLayout.LayoutParams qp=new LinearLayout.LayoutParams(NativeTheme.px(context,200),NativeTheme.px(context,200));qp.topMargin=NativeTheme.px(context,16);qp.bottomMargin=NativeTheme.px(context,10);content.addView(qr,qp);
                    content.addView(text(context,url,15));
                }else content.addView(text(context,"Connect this TV to Wi-Fi first.",15));
                TextView pin=text(context,"Pairing code  ·  "+access.pin,24);LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-2,-2);pp.topMargin=NativeTheme.px(context,14);content.addView(pin,pp);
                int phones=access.deviceCount();
                TextView hint=text(context,"Scanning connects your phone instantly — no code to type.\nEach phone gets its own connection; the code changes after each one.\n"
                        +(phones==0?"No phones paired yet.":phones==1?"1 phone paired.":phones+" phones paired."),12);hint.setGravity(Gravity.CENTER);hint.setTextColor(0xFFD4CEDA);
                LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);hp.topMargin=NativeTheme.px(context,12);content.addView(hint,hp);
            }else{
                TextView off=text(context,"The phone remote is off. Phones can’t control this TV.",15);off.setGravity(Gravity.CENTER);content.addView(off);
            }
        }catch(Exception e){content.addView(text(context,"Phone remote is starting. Close and reopen this page.",15));}
        View toggle=row(context,"remote-toggle","Phone remote",on?"On · phones on this Wi-Fi can pair":"Off · the TV doesn’t listen for phones",on,()->{
            worker.execute(()->{
                try{GlassRemoteAccess.setEnabled(context,!on);}catch(Exception ignored){}
                content.post(()->{AirPlayMonitorService.start(context,AirPlayMonitorService.ACTION_REMOTE_CHANGED);fill(content,"remote-toggle",false);});
            });
        });
        addRow(content,toggle,24);
        if(on){
            View forget=row(context,"remote-forget","Forget paired phones",
                    confirmForget?"Are you sure? All phones will be disconnected":"New code; phones paired before stop working",null,()->{
                if(!confirmForget){fill(content,"remote-forget",true);return;}
                worker.execute(()->{
                    try{GlassRemoteAccess.forgetAll(context);}catch(Exception ignored){}
                    content.post(()->{AirPlayMonitorService.start(context,AirPlayMonitorService.ACTION_REMOTE_CHANGED);fill(content,"remote-forget",false);});
                });
            });
            addRow(content,forget,10);
            if(confirmForget){
                // Read as a warning, not as another subtitle.
                View sub=forget.findViewWithTag("secondary");
                if(sub instanceof TextView){sub.setTag("warning");ControlCenterPanel.tint(sub,forget.hasFocus()||forget.isFocused());}
                // The confirmation is armed only briefly and only while this row
                // keeps focus, so a much later press cannot forget every phone.
                Runnable disarm=()->{
                    if(!forget.isAttachedToWindow())return;
                    View f=content.findFocus();
                    fill(content,f!=null&&f.getTag()!=null?f.getTag().toString():null,false);
                };
                forget.postDelayed(disarm,CONFIRM_MS);
                View.OnFocusChangeListener styling=forget.getOnFocusChangeListener();
                forget.setOnFocusChangeListener((v,f)->{
                    if(styling!=null)styling.onFocusChange(v,f);
                    if(!f){forget.removeCallbacks(disarm);content.post(disarm);}
                });
            }
        }
        if(focusTag!=null){View f=content.findViewWithTag(focusTag);if(f!=null)f.requestFocus();}
        else if(content.findFocus()==null)content.requestFocus(View.FOCUS_DOWN);
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
        Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();String fallback=null,ipv6=null;
        while(interfaces.hasMoreElements()){
            NetworkInterface network=interfaces.nextElement();if(!network.isUp()||network.isLoopback())continue;
            Enumeration<InetAddress> addresses=network.getInetAddresses();while(addresses.hasMoreElements()){
                InetAddress a=addresses.nextElement();if(a instanceof Inet4Address && a.isSiteLocalAddress()){
                    if(network.getName().startsWith("wlan")||network.getName().startsWith("eth"))return a.getHostAddress();fallback=a.getHostAddress();
                }else if(a instanceof java.net.Inet6Address&&!a.isLoopbackAddress()&&!a.isMulticastAddress()&&!a.isAnyLocalAddress()){
                    String host=a.getHostAddress();
                    if(host!=null&&(ipv6==null||!a.isLinkLocalAddress()))ipv6="["+host.replace("%","%25")+"]";
                }
            }
        }return fallback!=null?fallback:ipv6;
    }
    private GlassRemotePanel(){}
}
