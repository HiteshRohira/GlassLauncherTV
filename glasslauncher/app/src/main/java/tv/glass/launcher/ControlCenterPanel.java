package tv.glass.launcher;

import android.content.Context;
import android.content.res.ColorStateList;
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

/** One stationary glass surface with horizontally sliding pages. */
final class ControlCenterPanel extends FrameLayout {
    interface Host {
        String deviceName(); String networkName(); String networkStatus();
        String airStatus(); String airSummary(); String bluetoothSummary();
        void network(); void inputs(); void airplay(); void bluetooth(); void settings();
        View appearance(); View remote(); void shown(boolean visible);
    }
    private final Host host;
    private final List<List<View>> groups=new ArrayList<>();
    private final List<View> actions=new ArrayList<>();
    private FrameLayout card;
    private LinearLayout controls, detail;
    private View returnFocus;
    private String focusKey="network", detailKey;
    private boolean sliding;
    ControlCenterPanel(Context c,Host host) {
        super(c);this.host=host;
        setLayoutParams(new FrameLayout.LayoutParams(-1,-1));
        setVisibility(GONE);setFocusable(true);setClickable(true);
        setContentDescription("Control Center");setOnClickListener(v->hide());
    }
    boolean isVisible(){return getVisibility()==VISIBLE;}
    boolean isDetailVisible(String key){return isVisible() && key.equals(detailKey);}
    void show(View anchor,String key){
        returnFocus=anchor;if(key!=null)focusKey=key;
        rebuild();setVisibility(VISIBLE);bringToFront();host.shown(true);
        card.setAlpha(0);card.setScaleX(.97f);card.setScaleY(.97f);
        card.animate().alpha(1).scaleX(1).scaleY(1).setDuration(150).start();restoreFocus();
    }
    void hide(){
        if(!isVisible())return;
        cancelMotion();setVisibility(GONE);host.shown(false);
        if(returnFocus!=null && returnFocus.isShown())returnFocus.requestFocus();
    }
    void refresh(){if(isVisible() && detailKey==null && !sliding){rebuild();restoreFocus();}}
    void restoreFocus(){
        for(View v:actions)if(focusKey.equals(v.getTag())){v.requestFocus();return;}
        if(!actions.isEmpty())actions.get(0).requestFocus();
    }
    void openAppearance(){openDetail("appearance","Appearance",host.appearance());}
    private void openDetail(String key,String title,View content){
        if(sliding || detailKey!=null)return;
        detailKey=key;focusKey=key;
        detail=new LinearLayout(getContext());detail.setOrientation(LinearLayout.VERTICAL);
        detail.setPadding(px(22),px(22),px(22),px(22));
        View back=row("detail-back",title,null,R.drawable.ic_feather_chevron,this::backToControls,false,false);
        detail.addView(back,new LinearLayout.LayoutParams(-1,px(42)));
        ScrollView scroll=new ScrollView(getContext());scroll.setFillViewport(false);scroll.setClipToPadding(false);
        if(content.getParent() instanceof ViewGroup)((ViewGroup)content.getParent()).removeView(content);
        scroll.addView(content,new ScrollView.LayoutParams(-1,-2));
        LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,0,1);sp.topMargin=px(15);detail.addView(scroll,sp);
        if("appearance".equals(key)){content.setFocusable(true);content.setFocusableInTouchMode(true);}
        card.addView(detail,new FrameLayout.LayoutParams(-1,-1));
        int distance=px(422);detail.setTranslationX(distance);sliding=true;
        controls.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        controls.animate().translationX(-distance).setDuration(260).setInterpolator(new DecelerateInterpolator()).start();
        detail.animate().translationX(0).setDuration(260).setInterpolator(new DecelerateInterpolator())
            .withEndAction(()->{sliding=false;if("appearance".equals(key))content.requestFocus();else back.requestFocus();}).start();
    }
    void backToControls(){
        if(detailKey==null || sliding)return;
        sliding=true;final LinearLayout old=detail;
        controls.animate().translationX(0).setDuration(260).setInterpolator(new DecelerateInterpolator()).start();
        old.animate().translationX(px(422)).setDuration(260).setInterpolator(new DecelerateInterpolator()).withEndAction(()->{
            card.removeView(old);detail=null;detailKey=null;sliding=false;
            controls.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);restoreFocus();
        }).start();
    }
    boolean key(int key){
        if(sliding)return true;
        if(key==KeyEvent.KEYCODE_BACK || key==KeyEvent.KEYCODE_ESCAPE){if(detailKey!=null)backToControls();else hide();return true;}
        if(detailKey!=null)return false;
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
    private void cancelMotion(){if(card!=null)card.animate().cancel();if(controls!=null)controls.animate().cancel();if(detail!=null)detail.animate().cancel();sliding=false;}
    private void rebuild(){
        cancelMotion();removeAllViews();groups.clear();actions.clear();detailKey=null;detail=null;
        card=new FrameLayout(getContext());card.setBackground(NativeTheme.glass(getContext(),28,true));
        card.setElevation(px(15));card.setClipToOutline(true);card.setOnClickListener(v->{});
        FrameLayout.LayoutParams cp=new FrameLayout.LayoutParams(px(422),px(470),Gravity.TOP|Gravity.END);
        cp.topMargin=px(135);cp.setMarginEnd(px(68));addView(card,cp);
        controls=new LinearLayout(getContext());controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(px(22),px(22),px(22),px(22));card.addView(controls,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout header=new LinearLayout(getContext());header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(text("Control Center",23,false),new LinearLayout.LayoutParams(0,-2,1));header.addView(text(host.deviceName(),12,true));
        controls.addView(header,new LinearLayout.LayoutParams(-1,px(28)));
        LinearLayout quick=new LinearLayout(getContext());
        View net=row("network",host.networkName(),host.networkStatus(),R.drawable.ic_feather_wifi,host::network,false,true);
        View input=row("input","Inputs","Choose source",R.drawable.ic_feather_input,host::inputs,false,true);
        LinearLayout.LayoutParams qp=new LinearLayout.LayoutParams(0,px(60),1);qp.setMarginEnd(px(10));quick.addView(net,qp);quick.addView(input,new LinearLayout.LayoutParams(0,px(60),1));
        add(quick,12,60);groups.add(Arrays.asList(net,input));
        View air=row("airplay","AirPlay · "+host.airStatus(),host.airSummary(),R.drawable.ic_airplay,()->{hide();host.airplay();},true,true);
        add(air,9,55);groups.add(Arrays.asList(air));
        View remote=row("remote","Phone remote","Open on your phone",R.drawable.ic_btp_remote,()->openDetail("remote","Phone remote",host.remote()),true,true);
        add(remote,9,55);groups.add(Arrays.asList(remote));
        View settings=row("settings","System settings",null,R.drawable.ic_feather_settings,host::settings,true,true);
        add(settings,12,44);groups.add(Arrays.asList(settings));
        View appearance=row("appearance","Appearance",null,R.drawable.ic_feather_sliders,this::openAppearance,true,true);
        add(appearance,7,44);groups.add(Arrays.asList(appearance));
    }
    private void add(View v,int top,int height){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,px(height));lp.topMargin=px(top);controls.addView(v,lp);}
    private View row(String key,String title,String subtitle,int resource,Runnable action,boolean chevron,boolean track){
        LinearLayout row=new LinearLayout(getContext());row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(px(13),px(8),px(13),px(8));
        row.setFocusable(true);row.setFocusableInTouchMode(true);row.setClickable(true);row.setTag(key);row.setContentDescription(title+(subtitle==null?"":", "+subtitle));
        ImageView leading=icon(resource);if("detail-back".equals(key))leading.setRotation(180);
        row.addView(leading,new LinearLayout.LayoutParams(px(21),px(21)));
        LinearLayout words=new LinearLayout(getContext());words.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(0,-2,1);wp.setMarginStart(px(11));row.addView(words,wp);
        words.addView(text(title,15,false));if(subtitle!=null){TextView sub=text(subtitle,11,true);LinearLayout.LayoutParams sl=new LinearLayout.LayoutParams(-1,-2);sl.topMargin=px(3);words.addView(sub,sl);}
        if(chevron)row.addView(icon(R.drawable.ic_feather_chevron),new LinearLayout.LayoutParams(px(16),px(16)));
        row.setBackground(NativeTheme.row(getContext(),false,16));
        row.setOnClickListener(v->{v.requestFocus();if(track)focusKey=key;action.run();});
        row.setOnFocusChangeListener((v,f)->{if(f&&track)focusKey=key;row.setBackground(NativeTheme.row(getContext(),f,16));tint(row,f);});
        if(track)actions.add(row);return row;
    }
    private ImageView icon(int id){ImageView v=new ImageView(getContext());v.setImageResource(id);v.setImageTintList(ColorStateList.valueOf(0xFFF1EDF7));v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);return v;}
    private TextView text(String value,int size,boolean secondary){TextView v=new TextView(getContext());v.setText(value);v.setTypeface(NativeTheme.regular());v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,px(size));v.setTextColor(secondary?0xFFD4CEDA:0xFFF8F7FA);v.setTag(secondary?"secondary":"primary");v.setIncludeFontPadding(false);return v;}
    private void tint(View v,boolean focused){if(v instanceof TextView)((TextView)v).setTextColor(focused?("secondary".equals(v.getTag())?0xFF625C68:0xFF222029):("secondary".equals(v.getTag())?0xFFD4CEDA:0xFFF8F7FA));if(v instanceof ImageView)((ImageView)v).setImageTintList(ColorStateList.valueOf(focused?0xFF24212A:0xFFF1EDF7));if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)tint(((ViewGroup)v).getChildAt(i),focused);}
    private int px(float n){return NativeTheme.px(getContext(),n);}
}
