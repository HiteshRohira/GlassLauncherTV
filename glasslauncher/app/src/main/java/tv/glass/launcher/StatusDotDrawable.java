package tv.glass.launcher;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** A toolbar glyph with a small status dot at its lower right. Tint applies
 *  to the glyph only, so the dot keeps its colour. */
final class StatusDotDrawable extends Drawable {
    private final Drawable glyph;
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);

    StatusDotDrawable(Drawable glyph, int color) {
        this.glyph = glyph.mutate();
        dot.setColor(color);
        rim.setColor(0xB3141418);
    }

    @Override public void draw(Canvas c) {
        glyph.draw(c);
        Rect b = getBounds();
        float r = b.width() * 0.16f;
        float cx = b.right - r * 0.7f, cy = b.bottom - r * 0.7f;
        c.drawCircle(cx, cy, r * 1.35f, rim);
        c.drawCircle(cx, cy, r, dot);
    }

    @Override protected void onBoundsChange(Rect bounds) { glyph.setBounds(bounds); }
    @Override public int getIntrinsicWidth() { return glyph.getIntrinsicWidth(); }
    @Override public int getIntrinsicHeight() { return glyph.getIntrinsicHeight(); }
    @Override public void setTintList(ColorStateList tint) { glyph.setTintList(tint); }
    @Override public void setAlpha(int alpha) { glyph.setAlpha(alpha); dot.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter cf) { glyph.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
