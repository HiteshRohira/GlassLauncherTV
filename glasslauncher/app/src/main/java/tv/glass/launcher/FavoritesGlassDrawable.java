package tv.glass.launcher;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/** Lightweight glass surface shared by the home and drawer favorites rows. */
final class FavoritesGlassDrawable extends Drawable {

    private static final int[] HOME_FILL = {
            0x38FFFFFF, 0x4D141B28, 0x59101822
    };
    private static final int[] GRID_FILL = {
            0x28FFFFFF, 0x33141B28, 0x3D101822
    };
    private static final float[] FILL_STOPS = {0f, 0.38f, 1f};
    private static final int[] HOME_EDGE = {0x8AFFFFFF, 0x22FFFFFF};
    private static final int[] GRID_EDGE = {0x70FFFFFF, 0x1AFFFFFF};
    private static final int[] HOME_INNER_EDGE = {0x42FFFFFF, 0x08FFFFFF};
    private static final int[] GRID_INNER_EDGE = {0x30FFFFFF, 0x06FFFFFF};

    private final float cornerRadius;
    private final float outerStrokeWidth;
    private final float innerStrokeWidth;
    private final int[] fillColors;
    private final int[] edgeColors;
    private final int[] innerEdgeColors;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outerEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint innerEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF drawBounds = new RectF();
    private int drawableAlpha = 255;

    FavoritesGlassDrawable(float cornerRadius, float density, boolean quietGridStyle) {
        this.cornerRadius = cornerRadius;
        this.outerStrokeWidth = Math.max(1f, density);
        this.innerStrokeWidth = Math.max(1f, density * 0.55f);
        this.fillColors = quietGridStyle ? GRID_FILL : HOME_FILL;
        this.edgeColors = quietGridStyle ? GRID_EDGE : HOME_EDGE;
        this.innerEdgeColors = quietGridStyle ? GRID_INNER_EDGE : HOME_INNER_EDGE;

        fill.setStyle(Paint.Style.FILL);
        outerEdge.setStyle(Paint.Style.STROKE);
        outerEdge.setStrokeWidth(outerStrokeWidth);
        innerEdge.setStyle(Paint.Style.STROKE);
        innerEdge.setStrokeWidth(innerStrokeWidth);
    }

    @Override protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        if (bounds.isEmpty()) return;
        fill.setShader(new LinearGradient(0f, bounds.top, 0f, bounds.bottom,
                fillColors, FILL_STOPS, Shader.TileMode.CLAMP));
        outerEdge.setShader(new LinearGradient(0f, bounds.top, 0f, bounds.bottom,
                edgeColors, null, Shader.TileMode.CLAMP));
        innerEdge.setShader(new LinearGradient(0f, bounds.top, 0f, bounds.bottom,
                innerEdgeColors, null, Shader.TileMode.CLAMP));
    }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) return;
        drawBounds.set(bounds);
        int save = drawableAlpha < 255
                ? canvas.saveLayerAlpha(drawBounds, drawableAlpha)
                : canvas.save();
        try {
            canvas.drawRoundRect(drawBounds, cornerRadius, cornerRadius, fill);

            float outerInset = outerStrokeWidth / 2f;
            drawBounds.inset(outerInset, outerInset);
            canvas.drawRoundRect(drawBounds,
                    Math.max(0f, cornerRadius - outerInset),
                    Math.max(0f, cornerRadius - outerInset), outerEdge);

            float innerInset = outerStrokeWidth + innerStrokeWidth;
            drawBounds.inset(innerInset, innerInset);
            canvas.drawRoundRect(drawBounds,
                    Math.max(0f, cornerRadius - outerInset - innerInset),
                    Math.max(0f, cornerRadius - outerInset - innerInset), innerEdge);
        } finally {
            canvas.restoreToCount(save);
        }
    }

    @Override public void getOutline(Outline outline) {
        Rect bounds = getBounds();
        if (bounds.isEmpty()) outline.setEmpty();
        else outline.setRoundRect(bounds, cornerRadius);
    }

    @Override public void setAlpha(int alpha) {
        drawableAlpha = Math.max(0, Math.min(255, alpha));
        invalidateSelf();
    }

    @Override public void setColorFilter(ColorFilter colorFilter) {
        fill.setColorFilter(colorFilter);
        outerEdge.setColorFilter(colorFilter);
        innerEdge.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
