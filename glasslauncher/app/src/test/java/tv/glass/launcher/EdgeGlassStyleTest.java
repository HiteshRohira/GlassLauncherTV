package tv.glass.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Color;

import org.junit.Test;

/**
 * Pure-JVM contract test for {@link EdgeGlassStyle}.
 *
 * <p>These constants are the shared visual vocabulary the settings card and
 * the per-app context menu both draw from. Pinning them here guards against a
 * silent drift that would make the two edge-glass surfaces disagree, and
 * documents the colours the redesign is contractually required to preserve
 * (selected pill, selected/idle text, sky-blue value accent, destructive red).
 *
 * <p>No framework drawing classes are touched, so this runs on the plain JVM
 * with no Robolectric — the panel drawable itself (which constructs
 * {@code LinearGradient}) is exercised by the instrumentation test instead.
 */
public class EdgeGlassStyleTest {

    @Test
    public void preservedColourVocabulary_matchesLegacyLiterals() {
        // Values that existed before the redesign and MUST NOT change.
        assertEquals("selected pill", 0xFFEFEFEF, EdgeGlassStyle.SELECTED_PILL);
        assertEquals("selected text", 0xFF111114, EdgeGlassStyle.SELECTED_TEXT);
        assertEquals("idle text",     0xCCFFFFFF, EdgeGlassStyle.IDLE_TEXT);
        assertEquals("sky-blue value accent", 0xFF7DD3FC, EdgeGlassStyle.VALUE_ACCENT);
        assertEquals("destructive idle", 0xFFFF6B6B, EdgeGlassStyle.DESTRUCTIVE_IDLE);
        assertEquals("destructive selected", 0xFFC0202A, EdgeGlassStyle.DESTRUCTIVE_SEL);
        assertEquals("idle row background is transparent",
                Color.TRANSPARENT, EdgeGlassStyle.ROW_IDLE_BG);
    }

    @Test
    public void sharedGeometry_hasExpectedValues() {
        assertEquals("shared 14dp panel radius", 14, EdgeGlassStyle.PANEL_RADIUS_DP);
        assertEquals("shared row radius", 9, EdgeGlassStyle.ROW_RADIUS_DP);
        assertTrue("panel padding is positive", EdgeGlassStyle.PANEL_PADDING_DP > 0);
        assertTrue("panel elevation gives depth", EdgeGlassStyle.PANEL_ELEVATION_DP > 0);
        assertTrue("row gap is a small positive rhythm",
                EdgeGlassStyle.ROW_GAP_DP > 0 && EdgeGlassStyle.ROW_GAP_DP <= 6);
        assertTrue("menu has a sensible minimum width floor",
                EdgeGlassStyle.MENU_MIN_WIDTH_DP >= 100);
        assertTrue("menu edge margin is positive", EdgeGlassStyle.MENU_EDGE_MARGIN_DP > 0);
    }

    @Test
    public void dp_roundsToNearestPixelForGivenDensity() {
        // Mirrors LauncherActivity#dp (Math.round(v * density)).
        assertEquals(14, EdgeGlassStyle.dp(1.0f, 14));
        assertEquals(28, EdgeGlassStyle.dp(2.0f, 14));   // xhdpi
        assertEquals(21, EdgeGlassStyle.dp(1.5f, 14));   // hdpi
        assertEquals(0,  EdgeGlassStyle.dp(2.0f, 0));
        assertEquals("row radius px at density 1", 9,
                Math.round(EdgeGlassStyle.rowRadiusPx(1.0f)));
    }
}
