package tv.glass.launcher;

import android.app.*;
import android.content.*;
import android.media.tv.TvInputManager;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** In-sheet device pages. Only explicit user actions change system state. */
final class SystemControlPages {
    interface WifiBackend { JSONObject request(JSONObject request)throws Exception; }
    private final Activity activity;
    private final WifiBackend wifi;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private ExecutorService worker;
    private volatile int generation;
    private Future<?> pending;
    private LinearLayout page;
    private JSONObject snapshot;
    private Runnable fallback;
    private EditText password;
    private boolean joinForm;
    private boolean busy;
    SystemControlPages(Activity activity){this(activity,request->WifiControlClient.request(activity,request));}
    SystemControlPages(Activity activity,WifiBackend wifi){this.activity=activity;this.wifi=wifi;}
    View wifi(Runnable fallback) {
        cancel();this.fallback=fallback;page=column();
        note("Manage Wi-Fi here without leaving your app.");
        if(activity.getSharedPreferences("phone_remote_native",0).getBoolean("local_authorized",false))run("read",new JSONObject());
        else {note("Enable TV controls once, then approve Android’s authorization prompt on the TV.");action("enable","Enable TV controls",()->run("read",new JSONObject()));nativeWifi();}
        return page;
    }
    View inputs(Runnable fallback,Runnable close) {
        cancel();page=column();note("Choose a source to watch.");note("Loading inputs…");final int token=generation;
        executor().execute(()->{
            List<AppInfo> inputs=TvInputs.enumerate(activity);Map<String,String> states=new HashMap<>();
            TvInputManager manager=activity.getSystemService(TvInputManager.class);
            for(AppInfo input:inputs)try{states.put(input.tvInputId,manager!=null&&manager.getInputState(input.tvInputId)==TvInputManager.INPUT_STATE_DISCONNECTED?"Cable disconnected":"Available");}catch(RuntimeException ignored){states.put(input.tvInputId,"Status unavailable");}
            handler.post(()->{if(token!=generation)return;page.removeAllViews();note("Choose a source to watch.");
                if(inputs.isEmpty())note("No HDMI or AV inputs are exposed by this device.");
                for(AppInfo input:inputs)action(input.tvInputId,input.label+"\n"+states.get(input.tvInputId),()->{if(TvInputs.launch(activity,input.tvInputId,null))close.run();else toast("This input could not be opened");});
                action("input-settings","More input settings",fallback);focusFirst();
            });
        });return page;
    }
    boolean back(){if(joinForm){clearPassword();joinForm=false;render();focusFirst();return true;}return false;}
    void cancel(){generation++;if(pending!=null){pending.cancel(true);pending=null;}handler.removeCallbacksAndMessages(null);clearPassword();joinForm=false;busy=false;snapshot=null;page=null;}
    void close(){cancel();if(worker!=null){worker.shutdownNow();worker=null;}}
    private ExecutorService executor(){if(worker==null)worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"device-sheet");t.setDaemon(true);return t;});return worker;}
    private void clearPassword(){if(password!=null){
        android.view.inputmethod.InputMethodManager input=activity.getSystemService(android.view.inputmethod.InputMethodManager.class);
        if(input!=null && password.getWindowToken()!=null)input.hideSoftInputFromWindow(password.getWindowToken(),0);
        password.setText("");password=null;
    }}
    private void run(String op,JSONObject request) {
        if(busy || page==null)return;busy=true;clearPassword();joinForm=false;final int token=generation;
        String keep=page.findFocus()!=null && page.findFocus().getTag() instanceof String?(String)page.findFocus().getTag():null;
        page.removeAllViews();note("read".equals(op)?"Reading Wi-Fi…":"Applying Wi-Fi change…");
        try{request.put("op",op);}catch(JSONException ignored){}
        pending=executor().submit(()->{
            if(token!=generation)return;
            JSONObject result=null;try{result=wifi.request(request);}catch(Exception ignored){}
            final JSONObject answer=result;
            handler.post(()->{if(token!=generation)return;busy=false;
                if(answer==null){page.removeAllViews();note("Couldn’t reach TV controls. Check debugging is enabled and approve Android’s authorization prompt, then retry.");action("retry","Retry",()->run("read",new JSONObject()));nativeWifi();focusFirst();return;}
                snapshot=answer;activity.getSharedPreferences("phone_remote_native",0).edit().putBoolean("local_authorized",true).apply();render();
                if("join".equals(op)||"saved".equals(op))toast("Connection requested. Refresh to check its status.");
                if(!"read".equals(op)){final int scanToken=generation;handler.postDelayed(()->{if(scanToken==generation)run("read",new JSONObject());},2000);}
                View retained=keep==null?null:page.findViewWithTag(keep);if(retained!=null)retained.requestFocus();else focusFirst();
            });
        });
    }
    private void render() {
        if(page==null||snapshot==null)return;page.removeAllViews();
        boolean enabled=snapshot.optBoolean("enabled");int current=snapshot.optInt("currentId",-1);
        String ssid=unquote(snapshot.optString("ssid"));
        note(!enabled?"Wi-Fi is off":current>=0?ssid+"\nConnected · "+snapshot.optInt("linkMbps")+" Mbps":"Wi-Fi is on · not connected");
        action("toggle",enabled?"Turn Wi-Fi off":"Turn Wi-Fi on",()->{
            if(enabled)new AlertDialog.Builder(activity).setTitle("Turn Wi-Fi off?").setMessage("Casting and network playback will disconnect.").setNegativeButton("Keep on",null).setPositiveButton("Turn off",(d,w)->run("enabled",json("enabled",false))).show();
            else run("enabled",json("enabled",true));
        });
        if(enabled) {
            JSONArray saved=snapshot.optJSONArray("saved");Set<String> savedNames=new HashSet<>();
            if(saved!=null && saved.length()>0)note("Saved networks");
            if(saved!=null)for(int i=0;i<Math.min(saved.length(),64);i++){
                JSONObject network=saved.optJSONObject(i);if(network==null)continue;String name=network.optString("ssid");int id=network.optInt("id",-1);if(id<0)continue;savedNames.add(name);
                action("saved-"+id,name+(id==current?" · Connected":" · Saved"),()->{if(id!=current)run("saved",json("id",id));});
            }
            note("Nearby networks");
            LinkedHashMap<String,JSONObject> strongest=new LinkedHashMap<>();JSONArray scans=snapshot.optJSONArray("networks");
            if(scans!=null)for(int i=0;i<scans.length();i++){JSONObject n=scans.optJSONObject(i);if(n==null)continue;String name=n.optString("ssid");if(name.isEmpty()||savedNames.contains(name))continue;JSONObject old=strongest.get(name);if(old==null||old.optInt("rssi")<n.optInt("rssi"))strongest.put(name,n);}
            List<JSONObject> networks=new ArrayList<>(strongest.values());networks.sort((a,b)->Integer.compare(b.optInt("rssi"),a.optInt("rssi")));
            if(networks.isEmpty())note("No additional networks in the last scan. Select Scan to update.");
            for(JSONObject network:networks.subList(0,Math.min(24,networks.size()))){
                String name=network.optString("ssid"),security=security(network.optString("capabilities"));
                long age=Math.max(0,(SystemClock.elapsedRealtime()*1000-network.optLong("seenMicros"))/1000000);
                action("scan-"+name,name+"\n"+("open".equals(security)?"Open":"unsupported".equals(security)?"Use TV settings":"Secured")+" · "+age+"s ago",()->join(network,security));
            }
            action("scan","Scan for networks",()->run("scan",new JSONObject()));
        }
        action("refresh","Refresh status",()->run("read",new JSONObject()));nativeWifi();
    }
    private void join(JSONObject network,String security) {
        if("unsupported".equals(security)){toast("This network needs the TV’s advanced Wi-Fi settings");return;}
        page.removeAllViews();joinForm=true;String ssid=network.optString("ssid");note(ssid);
        note("open".equals(security)?"This network has no password or encryption.":"Enter the network password. It is sent to Android and is not saved by Mirage.");
        password=new EditText(activity);password.setSingleLine(true);password.setSaveEnabled(false);password.setHint("Password");password.setTextColor(0xFFF1EDF7);password.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        if(!"open".equals(security))page.addView(password,new LinearLayout.LayoutParams(-1,px(58)));
        action("connect","Connect",()->{
            String secret=password.getText().toString();if(!"open".equals(security)&&(secret.length()<8||secret.length()>63)){toast("Enter a password of 8–63 characters");return;}
            JSONObject request=json("ssid",ssid);try{request.put("security",security).put("password",secret);}catch(JSONException ignored){}run("join",request);
        });
        action("cancel","Back to networks",()->{clearPassword();joinForm=false;render();focusFirst();});focusFirst();
    }
    static String security(String flags){String f=flags.toUpperCase(Locale.ROOT);if(f.contains("EAP")||f.contains("WEP")||f.contains("OWE")||f.contains("WAPI")||f.contains("DPP"))return "unsupported";if(f.contains("PSK"))return "wpa2";if(f.contains("SAE"))return Build.VERSION.SDK_INT>=30?"wpa3":"unsupported";if(f.contains("WPA")||f.contains("RSN")||f.contains("WAPI")||f.contains("DPP")||!f.contains("ESS"))return "unsupported";return "open";}
    private void nativeWifi(){action("wifi-settings","Advanced Wi-Fi settings",fallback);}
    private static JSONObject json(String key,Object value){JSONObject o=new JSONObject();try{o.put(key,value);}catch(JSONException ignored){}return o;}
    private LinearLayout column(){LinearLayout v=new LinearLayout(activity);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private void note(String value){TextView text=new TextView(activity);text.setText(value);text.setTextColor(0xBBF1EDF7);text.setTextSize(14);text.setTypeface(NativeTheme.regular());text.setPadding(px(6),px(10),px(6),px(10));page.addView(text,new LinearLayout.LayoutParams(-1,-2));}
    private void action(String tag,String label,Runnable click){TextView row=new TextView(activity);row.setTag(tag);row.setText(label);row.setTextColor(0xFFF1EDF7);row.setTypeface(NativeTheme.regular());row.setTextSize(16);row.setPadding(px(16),px(15),px(16),px(15));row.setFocusable(true);row.setClickable(true);row.setBackground(NativeTheme.row(activity,false,18));row.setOnFocusChangeListener((v,f)->{row.setBackground(NativeTheme.row(activity,f,18));row.setTextColor(f?0xFF171B21:0xFFF1EDF7);});row.setOnClickListener(v->click.run());LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=px(8);page.addView(row,lp);}
    private void focusFirst(){if(page==null)return;for(int i=0;i<page.getChildCount();i++){View v=page.getChildAt(i);if(v.isFocusable()){v.requestFocus();return;}}}
    private int px(float dp){return NativeTheme.px(activity,dp);}
    private void toast(String text){Toast.makeText(activity,text,Toast.LENGTH_SHORT).show();}
    private static String unquote(String text){return text.startsWith("\"")&&text.endsWith("\"")&&text.length()>1?text.substring(1,text.length()-1):text;}
}
