package tv.glass.launcher;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.FocusFinder;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Control Center: a full-height glass sheet on the right edge. The main page
 *  is a grid of large status tiles; AirPlay, Phone remote and Appearance slide
 *  in as pages of the same sheet. Bluetooth uses {@link BluetoothPanel}, which
 *  draws the identical sheet so moving between them reads as one surface. */
final class ControlCenterPanel extends FrameLayout {
    interface Host {
        String deviceName(); String networkName(); String networkStatus();
        AirPlayMonitorService.Snapshot air(); boolean airLegacyInstalled();
        String bluetoothSummary(); String remoteSummary();
        void network(); void inputs(); void bluetooth(); void settings();
        default boolean usesPictureTile(){return false;}
        default void picture(){}
        void setAirEnabled(boolean on); void restartAir(); void showAirPlayer(); void setAirDebug(boolean on);
        View appearance(); View remote(); void shown(boolean visible);
    }
    static final int SHEET_W = 440;
    private static final int PAD = 26;
    private final Host host;
    private final SystemControlPages systemPages;
    private final List<List<View>> groups=new ArrayList<>();
    private final List<View> actions=new ArrayList<>();
    /** key -> {title, status, dot or null, tile} for in-place updates. */
    private final java.util.Map<String,View[]> tileParts=new java.util.HashMap<>();
    private FrameLayout card;
    private LinearLayout controls, detail;
    private View returnFocus;
    private TextView deviceLabel;
    private android.animation.ValueAnimator scrimMotion;
    private String focusKey="network", detailKey;
    private boolean sliding;
    private Runnable pendingBack;
    /** Last AirPlay headline spoken to a screen reader. */
    private String lastAnnounced;
    ControlCenterPanel(Context c,Host host) {
        super(c);this.host=host;systemPages=c instanceof android.app.Activity?new SystemControlPages((android.app.Activity)c):null;
        setLayoutParams(new FrameLayout.LayoutParams(-1,-1));
        setVisibility(GONE);setFocusable(true);setClickable(true);
        setContentDescription("Control Center");setOnClickListener(v->hide());
        setBackgroundColor(0x40000000);
    }
    void prepare(){if(card==null)rebuild();}
    boolean isVisible(){return getVisibility()==VISIBLE;}
    boolean isTransitioning(){return sliding;}
    /** Coalesce Back taps while a page moves, then use the caller's real navigator. */
    boolean deferBack(Runnable action){
        if(!sliding)return false;
        pendingBack=action;return true;
    }
    private void finishTransition(Runnable finish){
        sliding=false;
        Runnable back=pendingBack;pendingBack=null;
        finish.run();
        if(back!=null && isVisible())back.run();
    }
    boolean isDetailVisible(String key){return isVisible() && key.equals(detailKey);}
    void show(View anchor,String key){ show(anchor,key,true); }
    /** {@code animate=false} when returning from the Bluetooth sheet, which
     *  occupies the same place, so the switch reads as a page change. */
    void show(View anchor,String key,boolean animate){
        if(io.github.jqssun.airplay.diagnostics.PerfTrace.ENABLED && getContext() instanceof LauncherActivity)
            ((LauncherActivity)getContext()).measurePanel("control_center",this::isVisible,
                    ()->isVisible() && !sliding && card!=null && card.isLaidOut()
                            && card.getAlpha()>=.999f && Math.abs(card.getTranslationX())<.5f && findFocus()!=null);
        returnFocus=anchor;if(key!=null)focusKey=key;
        prepareControls();setVisibility(VISIBLE);bringToFront();host.shown(true);
        setAlpha(1f);
        if(animate){
            NativeTheme.sheetIn(card);
            // Fade the drawable without compositing the entire full-screen group.
            getBackground().setAlpha(0);
            scrimMotion=android.animation.ValueAnimator.ofInt(0,255);
            scrimMotion.setDuration(160);
            scrimMotion.addUpdateListener(a->getBackground().setAlpha((Integer)a.getAnimatedValue()));
            scrimMotion.start();
        } else {getBackground().setAlpha(255);}
        restoreFocus();
        if("airplay".equals(key))post(this::openAirPlay);
    }
    void hide(){ hide(true); }
    void hide(boolean restoreReturnFocus){
        if(!isVisible())return;
        cancelMotion();if(systemPages!=null)systemPages.cancel();setVisibility(GONE);host.shown(false);
        if(restoreReturnFocus && returnFocus!=null && returnFocus.isShown())returnFocus.requestFocus();
    }
    void refresh(){
        if(!isVisible() || sliding)return;
        if(detailKey==null)updateTiles();
        else if("airplay".equals(detailKey))fillAirPlay();
    }
    /** Glass: status pings update tile text and dots in place; rebuilding the
     *  whole sheet on each ping flickered focus and restarted animations. */
    private void updateTiles(){
        AirPlayMonitorService.Snapshot air=host.air();
        boolean netOk="Connected".equals(host.networkStatus());
        setTile("network",host.networkName(),host.networkStatus(),netOk?0xFF6ED69A:0xFFFF7A6E);
        setTile("bluetooth","Bluetooth",host.bluetoothSummary(),0);
        setTile("airplay","AirPlay",airHeadline(air),airColor(air));
        setTile("remote","Phone remote",host.remoteSummary(),0);
    }
    private void setTile(String key,String title,String status,int dotColor){
        View[] parts=tileParts.get(key);if(parts==null)return;
        if(!android.text.TextUtils.equals(((TextView)parts[0]).getText(),title))((TextView)parts[0]).setText(title);
        if(!android.text.TextUtils.equals(((TextView)parts[1]).getText(),status))((TextView)parts[1]).setText(status);
        if(parts[2]!=null&&dotColor!=0)((GradientDrawable)parts[2].getBackground()).setColor(dotColor);
        parts[3].setContentDescription(title+", "+status);
    }
    void restoreFocus(){
        if(detail!=null){
            if(detail.findFocus()==null){View first=firstFocusable(detail);if(first!=null)first.requestFocus();}
            return;
        }
        for(View v:actions)if(focusKey.equals(v.getTag())){v.requestFocus();return;}
        if(!actions.isEmpty())actions.get(0).requestFocus();
    }
    void openAppearance(){openDetail("appearance","Appearance",host.appearance());}
    void openAirPlay(){
        LinearLayout page=new LinearLayout(getContext());page.setOrientation(LinearLayout.VERTICAL);
        openDetail("airplay","AirPlay",page);fillAirPlay();
    }
    private LinearLayout airPage(){
        if(detail==null)return null;
        View scroll=detail.getChildAt(1);
        if(!(scroll instanceof ScrollView))return null;
        View page=((ScrollView)scroll).getChildAt(0);
        return page instanceof LinearLayout?(LinearLayout)page:null;
    }
    /** (Re)fill the AirPlay page in place, keeping the focused action. */
    private void fillAirPlay(){
        LinearLayout page=airPage();if(page==null)return;
        View f=page.findFocus();String keep=f!=null&&f.getTag()!=null?f.getTag().toString():null;
        page.removeAllViews();
        AirPlayMonitorService.Snapshot s=host.air();
        boolean on=s.state!=AirPlayStatus.State.OFF;
        LinearLayout status=new LinearLayout(getContext());status.setOrientation(LinearLayout.VERTICAL);
        status.setPadding(px(20),px(20),px(20),px(20));status.setBackground(NativeTheme.row(getContext(),false,20));
        LinearLayout top=new LinearLayout(getContext());top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(icon(R.drawable.ic_airplay),new LinearLayout.LayoutParams(px(30),px(30)));
        LinearLayout.LayoutParams dp=new LinearLayout.LayoutParams(px(10),px(10));dp.setMarginStart(px(12));
        top.addView(dot(airColor(s)),dp);
        String headline=airHeadline(s);
        if(lastAnnounced!=null&&!headline.equals(lastAnnounced))NativeTheme.announce(page,"AirPlay "+headline);
        lastAnnounced=headline;
        TextView state=text(headline,13,true);LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-2,-2);sp.setMarginStart(px(8));top.addView(state,sp);
        status.addView(top);
        TextView name=text(s.name.isEmpty()?host.deviceName():s.name,26,false);name.setTypeface(NativeTheme.light());
        LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,-2);np.topMargin=px(16);status.addView(name,np);
        TextView how=text(airExplain(s),13,true);how.setLineSpacing(px(3),1f);
        LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);hp.topMargin=px(8);status.addView(how,hp);
        page.addView(status,new LinearLayout.LayoutParams(-1,-2));
        List<View> rows=new ArrayList<>();
        if(s.inSession())rows.add(row("air-show","Show on TV","Bring the AirPlay screen back",R.drawable.ic_feather_chevron,host::showAirPlayer,false,false));
        rows.add(toggleRow("air-toggle","AirPlay receiver",on?"On · visible to Apple devices":"Off · hidden from Apple devices",on,()->host.setAirEnabled(!on)));
        if(on)rows.add(row("air-restart","Restart receiver",s.inSession()?"Ends the current session":"Use if your iPhone can’t find this TV",R.drawable.ic_feather_refresh,host::restartAir,false,false));
        if(host.airLegacyInstalled())rows.add(row("air-legacy","Old AirPlay app installed","Select to remove it, so this TV isn’t listed twice",R.drawable.ic_feather_warning,()->{
            try{getContext().startActivity(new android.content.Intent(android.content.Intent.ACTION_DELETE,
                    android.net.Uri.parse("package:"+AirPlayMonitorService.LEGACY_RECEIVER_PKG)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));}
            catch(RuntimeException ignored){}
        },false,false));
        if(on)rows.add(toggleRow("air-debug","Detailed logging",s.debug?"On · for diagnosing a problem; turn off after":"Off",s.debug,()->host.setAirDebug(!s.debug)));
        for(View r:rows){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,px(64));lp.topMargin=px(10);page.addView(r,lp);}
        if(on&&!s.diagnostics.isEmpty()){
            TextView diag=text("Diagnostics · "+s.diagnostics,12,true);diag.setLineSpacing(px(2),1f);diag.setFocusable(false);
            LinearLayout.LayoutParams gp=new LinearLayout.LayoutParams(-1,-2);gp.topMargin=px(14);gp.setMarginStart(px(6));gp.setMarginEnd(px(6));page.addView(diag,gp);
        }
        View target=null;
        for(View r:rows)if(keep!=null&&keep.equals(r.getTag()))target=r;
        if(target==null && !rows.isEmpty())target=rows.get(0);
        if(target!=null && !sliding)target.requestFocus();
    }
    private static String airHeadline(AirPlayMonitorService.Snapshot s){
        if(s.state==AirPlayStatus.State.OFF)return "Off";
        switch(s.session){
            case "mirroring":return "Mirroring now";
            case "video":return "Playing video";
            case "audio":return "Playing audio";
            case "connecting":return "Connecting";
            default:return s.state==AirPlayStatus.State.READY?"Ready":s.state==AirPlayStatus.State.FAILED?"Not visible":"Starting";
        }
    }
    private String airExplain(AirPlayMonitorService.Snapshot s){
        String name=s.name.isEmpty()?host.deviceName():s.name;
        if(s.state==AirPlayStatus.State.OFF)return "Turn AirPlay on to mirror or play from an iPhone, iPad or Mac.";
        switch(s.session){
            case "mirroring":return "On the AirPlay screen, press Back twice to stop mirroring. Your iPhone controls what is shown.";
            case "video":return "On the AirPlay screen, Back stops the video. Your iPhone controls playback.";
            case "audio":return "Back leaves the music screen; the music keeps playing. Your iPhone controls playback.";
            case "connecting":return "An Apple device is connecting.";
            default:break;
        }
        if(s.state==AirPlayStatus.State.FAILED)return "Your devices can’t see this TV right now. Mirage keeps retrying; Restart usually fixes it.";
        return "On your iPhone, open Control Center → Screen Mirroring and choose “"+name+"”.";
    }
    static int airColor(AirPlayMonitorService.Snapshot s){
        if(s.state==AirPlayStatus.State.OFF)return 0x66FFFFFF;
        if(s.inSession())return 0xFF7DB4FF;
        return s.state==AirPlayStatus.State.READY?0xFF6ED69A:s.state==AirPlayStatus.State.FAILED?0xFFFF7A6E:0xFFF2C46B;
    }
    void openNetwork(){if(systemPages!=null)openDetail("network","Wi-Fi",systemPages.wifi(host::network));else host.network();}
    void openInputs(){if(systemPages!=null)openDetail("input","Inputs",systemPages.inputs(host::inputs,this::hide));else host.inputs();}
    void openPicture(){host.picture();}
    @Override protected void onDetachedFromWindow(){if(systemPages!=null)systemPages.close();super.onDetachedFromWindow();}
    private void openDetail(String key,String title,View content){
        if(sliding || detailKey!=null)return;
        detailKey=key;focusKey=key;
        detail=new LinearLayout(getContext());detail.setOrientation(LinearLayout.VERTICAL);
        detail.setPadding(px(PAD),px(PAD),px(PAD),px(PAD));
        View back=backRow(title);
        detail.addView(back,new LinearLayout.LayoutParams(-1,px(48)));
        ScrollView scroll=new ScrollView(getContext());scroll.setFillViewport(false);scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false);
        if(content.getParent() instanceof ViewGroup)((ViewGroup)content.getParent()).removeView(content);
        scroll.addView(content,new ScrollView.LayoutParams(-1,-2));
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,0,1);sp.topMargin=px(18);detail.addView(scroll,sp);
        if("appearance".equals(key)){content.setFocusable(true);content.setFocusableInTouchMode(true);}
        card.addView(detail,new FrameLayout.LayoutParams(-1,-1));
        int distance=px(SHEET_W);detail.setTranslationX(distance);sliding=true;
        controls.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        controls.animate().translationX(-distance).alpha(0).setDuration(260).setInterpolator(new DecelerateInterpolator()).start();
        detail.animate().translationX(0).setDuration(260).setInterpolator(new DecelerateInterpolator())
            .withEndAction(()->finishTransition(()->{
                if("appearance".equals(key))content.requestFocus();
                else{View first=firstFocusable(content);if(first!=null)first.requestFocus();else back.requestFocus();}
            })).start();
    }
    private static View firstFocusable(View v){
        if(v.isFocusable() && v.getVisibility()==VISIBLE && !(v instanceof ViewGroup && ((ViewGroup)v).getChildCount()>0 && !v.isClickable()))return v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View f=firstFocusable(((ViewGroup)v).getChildAt(i));if(f!=null)return f;}
        return null;
    }
    void backToControls(){
        if(detailKey==null || sliding)return;
        if(systemPages!=null)systemPages.cancel();
        sliding=true;final LinearLayout old=detail;
        controls.animate().translationX(0).alpha(1).setDuration(260).setInterpolator(new DecelerateInterpolator()).start();
        old.animate().translationX(px(SHEET_W)).setDuration(260).setInterpolator(new DecelerateInterpolator()).withEndAction(()->finishTransition(()->{
            card.removeView(old);detail=null;detailKey=null;
            controls.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);rebuildKeepingPosition();
        })).start();
    }
    private void rebuildKeepingPosition(){rebuild();restoreFocus();}
    /** Dispatch both edges to native Appearance widgets without letting the
     *  window's fallback focus search reach the home screen at a slider edge. */
    boolean dispatchAppearanceWidgetKey(KeyEvent event){
        if(isDetailVisible("network") && findFocus() instanceof android.widget.EditText
                && event.getKeyCode()!=KeyEvent.KEYCODE_BACK && event.getKeyCode()!=KeyEvent.KEYCODE_ESCAPE
                && event.getKeyCode()!=KeyEvent.KEYCODE_DPAD_UP && event.getKeyCode()!=KeyEvent.KEYCODE_DPAD_DOWN){
            super.dispatchKeyEvent(event);return true;
        }
        if(!isDetailVisible("appearance") || sliding)return false;
        switch(event.getKeyCode()){
            case KeyEvent.KEYCODE_DPAD_LEFT:case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_CENTER:case KeyEvent.KEYCODE_ENTER:case KeyEvent.KEYCODE_BUTTON_A:
                super.dispatchKeyEvent(event);return true;
            default:return false;
        }
    }
    boolean key(int key){
        if(sliding){
            if(key==KeyEvent.KEYCODE_BACK || key==KeyEvent.KEYCODE_ESCAPE)deferBack(()->key(key));
            return true;
        }
        if(key==KeyEvent.KEYCODE_BACK || key==KeyEvent.KEYCODE_ESCAPE){if(systemPages!=null && systemPages.back())return true;if(detailKey!=null)backToControls();else hide();return true;}
        if(detailKey!=null){
            if(key==KeyEvent.KEYCODE_DPAD_UP || key==KeyEvent.KEYCODE_DPAD_DOWN){
                int direction=key==KeyEvent.KEYCODE_DPAD_UP?View.FOCUS_UP:View.FOCUS_DOWN;
                View focused=detail.findFocus();
                View next=focused==null?firstFocusable(detail)
                        :FocusFinder.getInstance().findNextFocus(detail,focused,direction);
                if(next!=null)next.requestFocus(direction);
                // At either edge, keep focus on the current row. Falling through
                // to the activity lets Android search the apps behind the sheet.
                return true;
            }
            if(!"appearance".equals(detailKey) && (key==KeyEvent.KEYCODE_DPAD_CENTER || key==KeyEvent.KEYCODE_ENTER || key==KeyEvent.KEYCODE_BUTTON_A)){
                View selected=findFocus();if(selected!=null && selected.isClickable())selected.performClick();return true;
            }
            // Keep focus inside the sheet: LEFT/RIGHT never escape to the home row.
            return key==KeyEvent.KEYCODE_DPAD_LEFT || key==KeyEvent.KEYCODE_DPAD_RIGHT
                    ? !"appearance".equals(detailKey) : false;
        }
        int row=0,col=0;
        for(int r=0;r<groups.size();r++)for(int c=0;c<groups.get(r).size();c++)if(groups.get(r).get(c).hasFocus()){row=r;col=c;}
        switch(key){
            case KeyEvent.KEYCODE_DPAD_UP:row=Math.max(0,row-1);break;
            case KeyEvent.KEYCODE_DPAD_DOWN:row=Math.min(groups.size()-1,row+1);break;
            case KeyEvent.KEYCODE_DPAD_LEFT:col=Math.max(0,col-1);break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:col=Math.min(groups.get(row).size()-1,col+1);break;
            case KeyEvent.KEYCODE_DPAD_CENTER:case KeyEvent.KEYCODE_ENTER:case KeyEvent.KEYCODE_BUTTON_A:
                View selected=findFocus();if(selected!=null)selected.performClick();return true;
            default:return false;
        }
        groups.get(row).get(Math.min(col,groups.get(row).size()-1)).requestFocus();return true;
    }
    private void cancelMotion(){pendingBack=null;if(scrimMotion!=null){scrimMotion.cancel();scrimMotion=null;}if(card!=null)card.animate().cancel();if(controls!=null)controls.animate().cancel();if(detail!=null)detail.animate().cancel();animate().cancel();sliding=false;}
    private void prepareControls(){
        cancelMotion();
        if(card==null){rebuild();return;}
        if(detail!=null)card.removeView(detail);
        detail=null;detailKey=null;
        card.setAlpha(1f);card.setTranslationX(0f);
        controls.setAlpha(1f);controls.setTranslationX(0f);
        controls.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if(deviceLabel!=null)deviceLabel.setText(host.deviceName());
        updateTiles();
    }
    private void rebuild(){
        cancelMotion();removeAllViews();groups.clear();actions.clear();tileParts.clear();detailKey=null;detail=null;
        card=NativeTheme.sheet(getContext());card.setOnClickListener(v->{});
        addView(card,NativeTheme.sheetParams(getContext()));
        controls=new LinearLayout(getContext());controls.setOrientation(LinearLayout.VERTICAL);
        // Focus grows tiles into the existing gutters, inside the sheet outline.
        controls.setClipChildren(false);controls.setClipToPadding(false);
        controls.setPadding(px(PAD),px(PAD+4),px(PAD),px(PAD));card.addView(controls,new FrameLayout.LayoutParams(-1,-1));
        TextView title=text("Control Center",30,false);title.setTypeface(NativeTheme.light());
        controls.addView(title);
        TextView device=text(host.deviceName(),14,true);deviceLabel=device;
        LinearLayout.LayoutParams dp=new LinearLayout.LayoutParams(-2,-2);dp.topMargin=px(6);controls.addView(device,dp);

        AirPlayMonitorService.Snapshot air=host.air();
        View net=tile("network",R.drawable.ic_feather_wifi,host.networkName(),host.networkStatus(),"Connected".equals(host.networkStatus())?0xFF6ED69A:0xFFFF7A6E,this::openNetwork);
        View bt=tile("bluetooth",R.drawable.ic_bt,"Bluetooth",host.bluetoothSummary(),0,()->{focusKey="bluetooth";host.bluetooth();});
        View ap=tile("airplay",R.drawable.ic_airplay,"AirPlay",airHeadline(air),airColor(air),this::openAirPlay);
        View remote=tile("remote",R.drawable.ic_btp_remote,"Phone remote",host.remoteSummary(),0,()->openDetail("remote","Phone remote",host.remote()));
        View input=tile("input",R.drawable.ic_feather_input,"Inputs","HDMI & sources",0,this::openInputs);
        View look=tile(host.usesPictureTile()?"picture":"appearance",R.drawable.ic_feather_sliders,host.usesPictureTile()?"Picture":"Appearance",host.usesPictureTile()?"Brightness & display":"Layout & wallpaper",0,()->{if(host.usesPictureTile())openPicture();else openAppearance();});
        addTiles(18,net,bt);addTiles(12,ap,remote);addTiles(12,input,look);

        View spacer=new View(getContext());controls.addView(spacer,new LinearLayout.LayoutParams(-1,0,1));
        View settings=row("settings","System settings","Network, display, sound and more",R.drawable.ic_feather_settings,host::settings,true,true);
        controls.addView(settings,new LinearLayout.LayoutParams(-1,px(64)));groups.add(Arrays.asList(settings));
    }
    private void addTiles(int top,View a,View b){
        LinearLayout line=new LinearLayout(getContext());
        line.setClipChildren(false);line.setClipToPadding(false);
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(0,-1,1);ap.setMarginEnd(px(12));
        line.addView(a,ap);line.addView(b,new LinearLayout.LayoutParams(0,-1,1));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,px(104));lp.topMargin=px(top);
        controls.addView(line,lp);groups.add(Arrays.asList(a,b));
    }
    /** Large Control Center tile: icon and status dot on top, title and live
     *  status beneath. Focus turns it into the light plate. */
    private View tile(String key,int resource,String title,String status,int dotColor,Runnable action){
        LinearLayout t=new LinearLayout(getContext());t.setOrientation(LinearLayout.VERTICAL);
        t.setPadding(px(16),px(15),px(14),px(13));
        t.setFocusable(true);t.setFocusableInTouchMode(true);t.setClickable(true);t.setTag(key);
        t.setContentDescription(title+", "+status);
        LinearLayout top=new LinearLayout(getContext());top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(icon(resource),new LinearLayout.LayoutParams(px(24),px(24)));
        top.addView(new View(getContext()),new LinearLayout.LayoutParams(0,1,1));
        View dotView=dotColor!=0?dot(dotColor):null;
        if(dotView!=null)top.addView(dotView,new LinearLayout.LayoutParams(px(9),px(9)));
        t.addView(top,new LinearLayout.LayoutParams(-1,-2));
        t.addView(new View(getContext()),new LinearLayout.LayoutParams(-1,0,1));
        TextView titleView=text(title,16,false);t.addView(titleView);
        TextView sub=text(status,12,true);sub.setSingleLine(true);sub.setEllipsize(TextUtils.TruncateAt.END);
        tileParts.put(key,new View[]{titleView,sub,dotView,t});
        LinearLayout.LayoutParams sl=new LinearLayout.LayoutParams(-1,-2);sl.topMargin=px(4);t.addView(sub,sl);
        t.setBackground(NativeTheme.row(getContext(),false,20));
        t.setOnClickListener(v->{v.requestFocus();focusKey=key;action.run();});
        t.setOnFocusChangeListener((v,f)->{
            if(f)focusKey=key;t.setBackground(NativeTheme.row(getContext(),f,20));tint(t,f);
            t.animate().scaleX(f?1.03f:1f).scaleY(f?1.03f:1f).setDuration(120).start();
        });
        actions.add(t);return t;
    }
    private View dot(int color){View d=new View(getContext());GradientDrawable g=new GradientDrawable();g.setShape(GradientDrawable.OVAL);g.setColor(color);d.setBackground(g);d.setTag("dot");return d;}
    private View backRow(String title){
        LinearLayout row=new LinearLayout(getContext());row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(px(12),0,px(12),0);
        row.setFocusable(true);row.setFocusableInTouchMode(true);row.setClickable(true);row.setTag("detail-back");row.setContentDescription("Back");
        ImageView chevron=icon(R.drawable.ic_feather_chevron);chevron.setRotation(180);
        row.addView(chevron,new LinearLayout.LayoutParams(px(20),px(20)));
        TextView t=text(title,22,false);LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(0,-2,1);tp.setMarginStart(px(12));row.addView(t,tp);
        row.setBackground(NativeTheme.row(getContext(),false,16));row.getBackground().setAlpha(0);
        row.setOnClickListener(v->backToControls());
        row.setOnFocusChangeListener((v,f)->{row.setBackground(NativeTheme.row(getContext(),f,16));if(!f)row.getBackground().setAlpha(0);tint(row,f);});
        return row;
    }
    private View toggleRow(String key,String title,String subtitle,boolean on,Runnable action){
        LinearLayout row=(LinearLayout)row(key,title,subtitle,R.drawable.ic_airplay,action,false,false);
        View sw=NativeTheme.toggle(getContext(),on);
        row.addView(sw,new LinearLayout.LayoutParams(px(42),px(24)));
        NativeTheme.switchSemantics(row,on);
        return row;
    }
    private View row(String key,String title,String subtitle,int resource,Runnable action,boolean chevron,boolean track){
        LinearLayout row=new LinearLayout(getContext());row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(px(16),px(8),px(16),px(8));
        row.setFocusable(true);row.setFocusableInTouchMode(true);row.setClickable(true);row.setTag(key);row.setContentDescription(title+(subtitle==null?"":", "+subtitle));
        row.addView(icon(resource),new LinearLayout.LayoutParams(px(22),px(22)));
        LinearLayout words=new LinearLayout(getContext());words.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(0,-2,1);wp.setMarginStart(px(14));row.addView(words,wp);
        words.addView(text(title,16,false));
        if(subtitle!=null){TextView sub=text(subtitle,12,true);sub.setSingleLine(true);sub.setEllipsize(TextUtils.TruncateAt.END);LinearLayout.LayoutParams sl=new LinearLayout.LayoutParams(-1,-2);sl.topMargin=px(4);words.addView(sub,sl);}
        if(chevron)row.addView(icon(R.drawable.ic_feather_chevron),new LinearLayout.LayoutParams(px(16),px(16)));
        row.setBackground(NativeTheme.row(getContext(),false,18));
        row.setOnClickListener(v->{v.requestFocus();if(track)focusKey=key;action.run();});
        row.setOnFocusChangeListener((v,f)->{if(f&&track)focusKey=key;row.setBackground(NativeTheme.row(getContext(),f,18));tint(row,f);});
        if(track)actions.add(row);return row;
    }
    private ImageView icon(int id){ImageView v=new ImageView(getContext());v.setImageResource(id);v.setImageTintList(ColorStateList.valueOf(0xFFF1EDF7));v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);return v;}
    private TextView text(String value,int size,boolean secondary){TextView v=new TextView(getContext());v.setText(value);v.setTypeface(NativeTheme.regular());v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,NativeTheme.textPx(getContext(),size));v.setTextColor(secondary?0xFFD4CEDA:0xFFF8F7FA);v.setTag(secondary?"secondary":"primary");v.setIncludeFontPadding(false);return v;}
    static void tint(View v,boolean focused){if(v instanceof TextView&&"warning".equals(v.getTag()))((TextView)v).setTextColor(focused?0xFFB3261E:0xFFFF8A80);
        else if(v instanceof TextView)((TextView)v).setTextColor(focused?("secondary".equals(v.getTag())?0xFF625C68:0xFF222029):("secondary".equals(v.getTag())?0xFFD4CEDA:0xFFF8F7FA));if(v instanceof ImageView)((ImageView)v).setImageTintList(ColorStateList.valueOf(focused?0xFF24212A:0xFFF1EDF7));if(v instanceof NativeTheme.Toggle)((NativeTheme.Toggle)v).setFocusedStyle(focused);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)tint(((ViewGroup)v).getChildAt(i),focused);}
    private int px(float n){return NativeTheme.px(getContext(),n);}
}
