package tv.glass.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class At4kHomeLayoutTest {

    @Test
    public void cardsUseFiveByThreeAspectRatio() {
        At4kHomeLayout.Metrics metrics = At4kHomeLayout.calculate(1920, 1080, 1f);

        assertEquals(metrics.tileWidthPx * 3, metrics.tileHeightPx * 5, 2);
    }

    @Test
    public void runtimeColumnsResizeCardsAndPreserveFiveByThreeRatio() {
        At4kHomeLayout.Metrics four = At4kHomeLayout.calculate(1920, 1080, 1f, 4, 20);
        At4kHomeLayout.Metrics seven = At4kHomeLayout.calculate(1920, 1080, 1f, 7, 20);

        assertTrue(four.tileWidthPx > seven.tileWidthPx);
        assertEquals(four.tileWidthPx * 3, four.tileHeightPx * 5, 2);
        assertEquals(seven.tileWidthPx * 3, seven.tileHeightPx * 5, 2);
    }

    @Test
    public void fourHundredPixelCardUsesTvOsHeight() {
        assertEquals(240, At4kHomeLayout.tileHeightPx(400));
    }

    @Test
    public void cardCornerRadiusSupportsSquareAndThirtyPercentEndpoints() {
        assertEquals(0, At4kHomeLayout.cornerRadiusPx(160, 0));
        assertEquals(32, At4kHomeLayout.cornerRadiusPx(160, 20));
        assertEquals(48, At4kHomeLayout.cornerRadiusPx(160, 30));
    }

    @Test
    public void favoritesCardIsVerticallyCenteredInsideBarCell() {
        assertEquals(14, At4kHomeLayout.centeredTop(188, 160));
        assertEquals(0, At4kHomeLayout.centeredTop(160, 160));
    }

    @Test
    public void gridFavoritesPlateTracksFirstRow() {
        assertEquals(170, At4kHomeLayout.favoritesPlateTop(200, 30));
        assertEquals(358, At4kHomeLayout.favoritesPlateBottom(200, 30, 188));
    }

    @Test
    public void labelsAppearOnlyBelowFavoritesBoundary() {
        assertFalse(At4kHomeLayout.shouldShowLabel(0, 6));
        assertFalse(At4kHomeLayout.shouldShowLabel(5, 6));
        assertTrue(At4kHomeLayout.shouldShowLabel(6, 6));
        assertTrue(At4kHomeLayout.shouldShowLabel(12, 6));
    }

    @Test
    public void wallpaperBlursOnlyBelowFavoritesBoundary() {
        assertFalse(At4kHomeLayout.shouldBlurBackground(5, 6));
        assertTrue(At4kHomeLayout.shouldBlurBackground(6, 6));
    }
}
