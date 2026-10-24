package tv.glass.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/** Broad app-coloured light. Shaders change only on resize or app selection. */
final class AmbientBackgroundView extends View {
    private final Paint light = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint reflection = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int tint = Color.rgb(113, 43, 21);
    private final float[] hsv = new float[3];
    AmbientBackgroundView(Context c) {
        super(c);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    void select(Bitmap banner) {
        if (banner == null || banner.isRecycled()) return;
        float[] weights = new float[12];
        long[] red = new long[12], green = new long[12], blue = new long[12];
        int[] count = new int[12];
        int step = Math.max(2, Math.min(banner.getWidth(), banner.getHeight()) / 20);
        for (int y = 0; y < banner.getHeight(); y += step) {
            for (int x = 0; x < banner.getWidth(); x += step) {
                int c = banner.getPixel(x, y);
                if (Color.alpha(c) < 128) continue;
                Color.colorToHSV(c, hsv);
                if (hsv[1] < .25f || hsv[2] < .18f) continue;
                int bin = Math.min(11, (int)(hsv[0] / 30));
                weights[bin] += hsv[1] * hsv[2];
                red[bin] += Color.red(c); green[bin] += Color.green(c); blue[bin] += Color.blue(c);
                count[bin]++;
            }
        }
        int best = 0;
        for (int i = 1; i < 12; i++) if (weights[i] > weights[best]) best = i;
        int color = Color.rgb(36, 39, 55);
        if (count[best] > 0) {
            Color.colorToHSV(Color.rgb((int)(red[best]/count[best]),
                    (int)(green[best]/count[best]), (int)(blue[best]/count[best])), hsv);
            hsv[1] *= .83f; hsv[2] = .44f;
            color = Color.HSVToColor(hsv);
        }
        if (color != tint) { tint = color; rebuild(); invalidate(); }
    }
    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { rebuild(); }
    private void rebuild() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        int rgb = tint & 0x00FFFFFF;
        RadialGradient a = new RadialGradient(0, 0, 1,
                new int[]{0x73000000 | rgb, 0x00000000 | rgb}, null, Shader.TileMode.CLAMP);
        Matrix m = new Matrix(); m.setScale(w * .79f, h * .91f); m.postTranslate(w * .24f, h * .39f);
        a.setLocalMatrix(m); light.setShader(a);
        RadialGradient b = new RadialGradient(0, 0, 1,
                new int[]{0x27000000 | rgb, 0x00000000 | rgb}, null, Shader.TileMode.CLAMP);
        m = new Matrix(); m.setScale(w * .43f, h * .43f); m.postTranslate(w * .68f, h * 1.05f);
        b.setLocalMatrix(m); reflection.setShader(b);
    }
    @Override protected void onDraw(Canvas c) {
        c.drawColor(0xFF050609);
        c.drawRect(0, 0, getWidth(), getHeight(), light);
        c.drawRect(0, 0, getWidth(), getHeight(), reflection);
    }
}
