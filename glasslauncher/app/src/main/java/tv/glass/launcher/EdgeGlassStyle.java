package tv.glass.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;

final class EdgeGlassStyle {

    private EdgeGlassStyle() { /* no instances */ }

    // ── Shared panel geometry ────────────────────────────────────────────
    /** Outer corner radius shared by the settings card and the per-app menu. */
    static final int PANEL_RADIUS_DP = 14;
    /** Inner content padding shared by both panels. */
    static final int PANEL_PADDING_DP = 6;
    /** Resting elevation giving both panels a consistent lift off the shelf. */
    static final int PANEL_ELEVATION_DP = 10;

    // ── Row rhythm ───────────────────────────────────────────────────────
    /** Rounded-pill radius for a selected row inside either panel. */
    static final int ROW_RADIUS_DP = 9;
    /** Uniform gap between rows in either panel. */
    static final int ROW_GAP_DP = 3;
    /** Per-app menu adaptive-width floor (was a hard 140 dp). */
    static final int MENU_MIN_WIDTH_DP = 132;
    /** Screen-edge clamp margin used when positioning the per-app menu. */
    static final int MENU_EDGE_MARGIN_DP = 8;

    // ── Panel fill gradient (approximates the flat 0xF21C1C22 slate) ──────
    // A quiet top-to-bottom slate: a slightly lifted top so the bright rim
    // reads as a sheen, settling to the canonical 0xF21C1C22 mid and a
    // fractionally darker lower edge for depth. Alpha holds at 0xF2 across
    // all three stops so the panel opacity matches the previous flat fill.
    private static final int[] FILL_COLORS = {0xF226262E, 0xF21C1C22, 0xF2171719};
    private static final float[] FILL_STOPS = {0f, 0.42f, 1f};

    // ── Rim sheen (bright upper edge fading to a quiet lower edge) ────────
    // Derived from FavoritesGlassDrawable's HOME_EDGE / HOME_INNER_EDGE, so
    // the menus share the favorites row's glass vocabulary without adding a
    // second RenderEffect or blur layer.
    private static final int[] OUTER_RIM = {0x8AFFFFFF, 0x22FFFFFF};
    private static final int[] INNER_RIM = {0x30FFFFFF, 0x08FFFFFF};

    // ── Shared colour vocabulary (kept identical to the pre-refactor UI) ──
    /** Frosted-white selected pill fill. */
    static final int SELECTED_PILL   = 0xFFEFEFEF;
    /** Selected-row text (dark, for contrast on the frosted pill). */
    static final int SELECTED_TEXT   = 0xFF111114;
    /** Idle (unselected) row text. */
    static final int IDLE_TEXT       = 0xCCFFFFFF;
    /** Sky-blue value accent used on settings indicators. */
    static final int VALUE_ACCENT    = 0xFF7DD3FC;
    /** Destructive (Uninstall) idle text. */
    static final int DESTRUCTIVE_IDLE = 0xFFFF6B6B;
    /** Destructive (Uninstall) text when the row is selected. */
    static final int DESTRUCTIVE_SEL  = 0xFFC0202A;
    /** Transparent idle row background. */
    static final int ROW_IDLE_BG      = Color.TRANSPARENT;

    /** dp → px against the given density (matches LauncherActivity#dp). */
    static int dp(float density, int v) { return Math.round(v * density); }

    /**
     * Build the shared edge-glass panel background: a rounded slate fill with
     * a bright upper rim/sheen fading to a quiet lower edge. Both the settings
     * card and the per-app menu use this so their surfaces read as the same
     * primitive.
     *
     * <p>Each call returns a fresh {@link Drawable} — Android's drawable state
     * (bounds, alpha) is per-instance, and the shaders are rebuilt on bounds
     * change so a tall settings card and a short menu each get a correctly
     * scaled gradient. No {@code RenderEffect} or legacy blur is involved; the
     * "glass" is purely the gradient + rim strokes.
     *
     * @param density display density (Resources.getDisplayMetrics().density)
     */
    static Drawable makePanelBackground(float density) {
        return new PanelDrawable(dp(density, PANEL_RADIUS_DP), density);
    }

    /** Convenience: apply the shared radius / density panel background and the
     *  standard padding + elevation to a container view. */
    static void applyPanel(android.view.View v, float density) {
        v.setBackground(makePanelBackground(density));
        int pad = dp(density, PANEL_PADDING_DP);
        v.setPadding(pad, pad, pad, pad);
        v.setElevation(dp(density, PANEL_ELEVATION_DP));
    }

    /** Pixel size for the shared row-pill corner radius. */
    static float rowRadiusPx(float density) { return dp(density, ROW_RADIUS_DP); }

    private static final class PanelDrawable extends Drawable {
        private final float cornerRadius;
        private final float outerStroke;
        private final float innerStroke;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint outerEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint innerEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private int alpha = 255;

        PanelDrawable(float cornerRadius, float density) {
            this.cornerRadius = cornerRadius;
            this.outerStroke = Math.max(1f, density);
            this.innerStroke = Math.max(1f, density * 0.55f);
            fill.setStyle(Paint.Style.FILL);
            outerEdge.setStyle(Paint.Style.STROKE);
            outerEdge.setStrokeWidth(outerStroke);
            innerEdge.setStyle(Paint.Style.STROKE);
            innerEdge.setStrokeWidth(innerStroke);
        }

        @Override protected void onBoundsChange(Rect b) {
            if (b.isEmpty()) return;
            fill.setShader(new LinearGradient(0f, b.top, 0f, b.bottom,
                    FILL_COLORS, FILL_STOPS, Shader.TileMode.CLAMP));
            outerEdge.setShader(new LinearGradient(0f, b.top, 0f, b.bottom,
                    OUTER_RIM, null, Shader.TileMode.CLAMP));
            innerEdge.setShader(new LinearGradient(0f, b.top, 0f, b.bottom,
                    INNER_RIM, null, Shader.TileMode.CLAMP));
        }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            if (b.isEmpty()) return;
            int save = alpha < 255
                    ? c.saveLayerAlpha(b.left, b.top, b.right, b.bottom, alpha)
                    : c.save();
            try {
                rect.set(b);
                c.drawRoundRect(rect, cornerRadius, cornerRadius, fill);

                float outerInset = outerStroke / 2f;
                rect.inset(outerInset, outerInset);
                float or = Math.max(0f, cornerRadius - outerInset);
                c.drawRoundRect(rect, or, or, outerEdge);

                float innerInset = outerStroke + innerStroke;
                rect.inset(innerInset, innerInset);
                float ir = Math.max(0f, cornerRadius - outerInset - innerInset);
                c.drawRoundRect(rect, ir, ir, innerEdge);
            } finally {
                c.restoreToCount(save);
            }
        }

        @Override public void getOutline(Outline outline) {
            Rect b = getBounds();
            if (b.isEmpty()) outline.setEmpty();
            else outline.setRoundRect(b, cornerRadius);
        }

        @Override public void setAlpha(int a) {
            alpha = Math.max(0, Math.min(255, a));
            invalidateSelf();
        }

        @Override public void setColorFilter(ColorFilter cf) {
            fill.setColorFilter(cf);
            outerEdge.setColorFilter(cf);
            innerEdge.setColorFilter(cf);
            invalidateSelf();
        }

        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
