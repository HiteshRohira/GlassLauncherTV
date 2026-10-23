package tv.glass.launcher;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;

/** Shared, allocation-light material and type for the ambient launcher. */
final class NativeTheme {
    private static Typeface light, regular, medium;
    static synchronized void init(Context context) {
        if (regular != null) return;
        try {
            light = new Typeface.Builder(context.getAssets(), "fonts/outfit.ttf")
                    .setFontVariationSettings("'wght' 300").setWeight(300).build();
            regular = new Typeface.Builder(context.getAssets(), "fonts/outfit.ttf")
                    .setFontVariationSettings("'wght' 400").setWeight(400).build();
            medium = new Typeface.Builder(context.getAssets(), "fonts/outfit.ttf")
                    .setFontVariationSettings("'wght' 500").setWeight(500).build();
        } catch (RuntimeException ignored) {
            light = Typeface.create("sans-serif-light", Typeface.NORMAL);
            regular = Typeface.create("sans-serif", Typeface.NORMAL);
            medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        }
    }
    static Typeface light() { return light != null ? light : Typeface.DEFAULT; }
    static Typeface regular() { return regular != null ? regular : Typeface.DEFAULT; }
    static Typeface medium() { return medium != null ? medium : Typeface.DEFAULT; }
    static int px(Context c, float value) {
        return Math.round(value * c.getResources().getDisplayMetrics().widthPixels / 1280f);
    }
    static GradientDrawable glass(Context c, float radius, boolean panel) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                panel ? new int[]{0xE33B3C43, 0xEF181C27, 0xED28222E}
                      : new int[]{0x26FFFFFF, 0x0CFFFFFF, 0x18FFFFFF});
        d.setCornerRadius(px(c, radius));
        d.setStroke(Math.max(1, px(c, .8f)), panel ? 0x45FFFFFF : 0x29FFFFFF);
        return d;
    }
    static GradientDrawable row(Context c, boolean selected, float radius) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                selected ? new int[]{0xFFF0EEF3, 0xFFE3E1E7}
                         : new int[]{0x14FFFFFF, 0x07FFFFFF});
        d.setCornerRadius(px(c, radius));
        d.setStroke(Math.max(1, px(c, .7f)), selected ? 0x88FFFFFF : 0x16FFFFFF);
        return d;
    }
    private NativeTheme() { }
}
