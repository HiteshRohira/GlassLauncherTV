package tv.glass.launcher;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Native floating quick controls. Device state comes exclusively from the host. */
final class ControlCenterPanel extends FrameLayout {
    static final class Device {
        final String key, name, status;
        final int icon;
        final Runnable open;
        Device(String key, String name, String status, int icon, Runnable open) {
            this.key=key; this.name=name; this.status=status; this.icon=icon; this.open=open;
        }
    }
    interface Host {
        String deviceName();
        String networkName();
        String networkStatus();
        String airStatus();
        String airSummary();
        String bluetoothSummary();
        List<Device> devices();
        void network(); void inputs(); void airplay(); void bluetooth(); void pair(); void settings();
        void shown(boolean visible);
    }
    private final Host host;
    private final List<List<View>> groups = new ArrayList<>();
    private final List<View> actions = new ArrayList<>();
    private LinearLayout card;
    private View returnFocus;
    private String focusKey = "network";
    ControlCenterPanel(Context c, Host host) {
        super(c); this.host=host;
        setLayoutParams(new FrameLayout.LayoutParams(-1,-1));
        setVisibility(GONE); setFocusable(true); setClickable(true);
        setContentDescription("Control Center");
        setOnClickListener(v -> hide());
    }
    boolean isVisible() { return getVisibility()==VISIBLE; }
    void show(View anchor, String key) {
        returnFocus=anchor;
        if (key != null) focusKey=key;
        rebuild(); setVisibility(VISIBLE); bringToFront(); host.shown(true);
        card.setAlpha(0f); card.setScaleX(.97f); card.setScaleY(.97f);
        card.setPivotX(px(422)); card.setPivotY(0);
        card.animate().alpha(1).scaleX(1).scaleY(1).setDuration(150).start();
        restoreFocus();
    }
    void hide() {
        if (!isVisible()) return;
        card.animate().cancel(); setVisibility(GONE); host.shown(false);
        if (returnFocus != null && returnFocus.isShown()) returnFocus.requestFocus();
    }
    void refresh() { if (isVisible()) { rebuild(); restoreFocus(); } }
    void restoreFocus() {
        for (View v : actions) if (focusKey.equals(v.getTag())) { v.requestFocus(); return; }
        if (!actions.isEmpty()) actions.get(0).requestFocus();
    }
    boolean key(int key) {
        if (key==KeyEvent.KEYCODE_BACK || key==KeyEvent.KEYCODE_ESCAPE) { hide(); return true; }
        int row=0,col=0;
        for(int r=0;r<groups.size();r++) for(int c=0;c<groups.get(r).size();c++)
            if(groups.get(r).get(c).hasFocus()) { row=r;col=c; }
        switch(key) {
            case KeyEvent.KEYCODE_DPAD_UP: row=Math.max(0,row-1); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: row=Math.min(groups.size()-1,row+1); break;
            case KeyEvent.KEYCODE_DPAD_LEFT: col=Math.max(0,col-1); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: col=Math.min(groups.get(row).size()-1,col+1); break;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER:
                View selected=findFocus(); if(selected!=null) selected.performClick(); return true;
            default:return false;
        }
        groups.get(row).get(Math.min(col,groups.get(row).size()-1)).requestFocus(); return true;
    }
    private void rebuild() {
        String keep=focusKey;
        removeAllViews(); groups.clear(); actions.clear();
        card=new LinearLayout(getContext()); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(px(22),px(22),px(22),px(22));
        card.setBackground(NativeTheme.glass(getContext(),28,true));
        card.setElevation(px(15)); card.setOnClickListener(v -> {});
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(px(422),-2,Gravity.TOP|Gravity.END);
        lp.topMargin=px(135);lp.setMarginEnd(px(68));addView(card,lp);
        LinearLayout header=new LinearLayout(getContext()); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=text("Control Center",23,false); title.setTypeface(NativeTheme.regular());
        header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        header.addView(text(host.deviceName(),13,true));
        card.addView(header,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout quick=new LinearLayout(getContext());
        View network=row("network",host.networkName(),host.networkStatus(),R.drawable.ic_feather_wifi,host::network,false);
        View inputs=row("input","Inputs","Choose source",R.drawable.ic_feather_input,host::inputs,false);
        LinearLayout.LayoutParams a=new LinearLayout.LayoutParams(0,px(69),1);a.setMarginEnd(px(10));
        quick.addView(network,a);quick.addView(inputs,new LinearLayout.LayoutParams(0,px(69),1));
        add(quick,18);groups.add(Arrays.asList(network,inputs));
        View air=row("airplay","AirPlay · "+host.airStatus(),host.airSummary(),R.drawable.ic_airplay,()->{hide();host.airplay();},true);
        add(air,11);groups.add(Arrays.asList(air));
        LinearLayout btLabel=new LinearLayout(getContext());btLabel.setGravity(Gravity.CENTER_VERTICAL);
        btLabel.addView(icon(R.drawable.ic_bt,18),new LinearLayout.LayoutParams(px(18),px(18)));
        TextView bt=text("Paired Bluetooth devices",15,false);
        LinearLayout.LayoutParams bl=new LinearLayout.LayoutParams(-2,-2);bl.setMarginStart(px(8));btLabel.addView(bt,bl);add(btLabel,21);
        List<Device> devices=host.devices();
        if(devices.isEmpty()) {
            View b=row("bluetooth","Bluetooth",host.bluetoothSummary(),R.drawable.ic_bt,()->{hide();host.bluetooth();},true);
            add(b,9);groups.add(Arrays.asList(b));
        } else for(Device d:devices) {
            View v=row(d.key,d.name,d.status,d.icon,()->{hide();d.open.run();},true);
            v.setBackgroundColor(0x00000000);add(v,2);groups.add(Arrays.asList(v));
        }
        LinearLayout bottom=new LinearLayout(getContext());
        View pair=row("pair","Pair device",null,R.drawable.ic_btp_add,()->{hide();host.pair();},false);
        View settings=row("settings","Settings",null,R.drawable.ic_feather_settings,host::settings,false);
        LinearLayout.LayoutParams b=new LinearLayout.LayoutParams(0,px(42),1);b.setMarginEnd(px(9));
        bottom.addView(pair,b);bottom.addView(settings,new LinearLayout.LayoutParams(0,px(42),1));
        add(bottom,12);groups.add(Arrays.asList(pair,settings));focusKey=keep;
    }
    private void add(View v,int margin) {
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=px(margin);card.addView(v,lp);
    }
    private View row(String key,String title,String subtitle,int resource,Runnable action,boolean chevron) {
        LinearLayout row=new LinearLayout(getContext()); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(14),px(11),px(14),px(11));row.setMinimumHeight(px(subtitle==null?40:62));
        row.setFocusable(true);row.setFocusableInTouchMode(true);row.setClickable(true);row.setTag(key);
        row.setContentDescription(title+(subtitle==null?"":", "+subtitle));
        ImageView iv=icon(resource,subtitle==null?18:24);
        row.addView(iv,new LinearLayout.LayoutParams(px(subtitle==null?18:24),px(subtitle==null?18:24)));
        LinearLayout words=new LinearLayout(getContext());words.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams w=new LinearLayout.LayoutParams(0,-2,1);w.setMarginStart(px(subtitle==null?8:12));row.addView(words,w);
        words.addView(text(title,subtitle==null?13:15,false));
        if(subtitle!=null) { TextView sub=text(subtitle,11,true);LinearLayout.LayoutParams sl=new LinearLayout.LayoutParams(-1,-2);sl.topMargin=px(3);words.addView(sub,sl); }
        if(chevron) row.addView(icon(R.drawable.ic_feather_chevron,16),new LinearLayout.LayoutParams(px(16),px(16)));
        row.setBackground(NativeTheme.row(getContext(),false,16));
        row.setOnClickListener(v->{v.requestFocus();focusKey=key;action.run();});
        row.setOnFocusChangeListener((v,f)->{if(f) focusKey=key;row.setBackground(NativeTheme.row(getContext(),f,16));tint(row,f);});
        actions.add(row);return row;
    }
    private ImageView icon(int id,int size) {
        ImageView v=new ImageView(getContext());v.setImageResource(id);v.setImageTintList(ColorStateList.valueOf(0xFFF1EDF7));
        v.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);return v;
    }
    private TextView text(String value,int size,boolean secondary) {
        TextView v=new TextView(getContext());v.setText(value);v.setTypeface(NativeTheme.regular());v.setFontFeatureSettings("kern");
        v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,px(size));v.setTextColor(secondary?0xFFD4CEDA:0xFFF8F7FA);
        v.setTag(secondary?"secondary":"primary");v.setIncludeFontPadding(false);return v;
    }
    private void tint(View v,boolean focus) {
        if(v instanceof TextView) ((TextView)v).setTextColor(focus?("secondary".equals(v.getTag())?0xFF625C68:0xFF222029):("secondary".equals(v.getTag())?0xFFD4CEDA:0xFFF8F7FA));
        if(v instanceof ImageView) ((ImageView)v).setImageTintList(ColorStateList.valueOf(focus?0xFF24212A:0xFFF1EDF7));
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)tint(((ViewGroup)v).getChildAt(i),focus);
    }
    private int px(float v) { return NativeTheme.px(getContext(),v); }
}
