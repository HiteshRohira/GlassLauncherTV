package tv.glass.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WallpaperModesTest {

    @Test public void source_systemWinsThenSlideshowThenPicture() {
        assertEquals(WallpaperModes.SYSTEM, WallpaperModes.source(true, true));
        assertEquals(WallpaperModes.SYSTEM, WallpaperModes.source(true, false));
        assertEquals(WallpaperModes.SLIDESHOW, WallpaperModes.source(false, true));
        assertEquals(WallpaperModes.PICTURE, WallpaperModes.source(false, false));
    }

    @Test public void stepSource_wrapsBothWays() {
        assertEquals(WallpaperModes.SLIDESHOW, WallpaperModes.stepSource(WallpaperModes.PICTURE, 1));
        assertEquals(WallpaperModes.SYSTEM, WallpaperModes.stepSource(WallpaperModes.SLIDESHOW, 1));
        assertEquals(WallpaperModes.PICTURE, WallpaperModes.stepSource(WallpaperModes.SYSTEM, 1));
        assertEquals(WallpaperModes.SYSTEM, WallpaperModes.stepSource(WallpaperModes.PICTURE, -1));
    }

    @Test public void changeValue_eachRestartOverridesInterval() {
        assertEquals(WallpaperModes.EACH_RESTART, WallpaperModes.changeValue(0, true));
        assertEquals(45, WallpaperModes.changeValue(45, false));
    }

    @Test public void stepChange_walksRestartThenIntervalsAndWraps() {
        assertEquals(20, WallpaperModes.stepChange(WallpaperModes.EACH_RESTART, 1, 30));
        assertEquals(45, WallpaperModes.stepChange(30, 1, 30));
        assertEquals(WallpaperModes.EACH_RESTART, WallpaperModes.stepChange(180, 1, 30));
        assertEquals(180, WallpaperModes.stepChange(WallpaperModes.EACH_RESTART, -1, 30));
    }

    @Test public void stepChange_unknownValueLandsOnFallback() {
        assertEquals(30, WallpaperModes.stepChange(0, 1, 30));
        assertEquals(30, WallpaperModes.stepChange(17, -1, 30));
        assertEquals(20, WallpaperModes.stepChange(0, 1, 999));
    }

    @Test public void isValidInterval_acceptsOffAndOfferedIntervalsOnly() {
        assertTrue(WallpaperModes.isValidInterval(0));
        assertTrue(WallpaperModes.isValidInterval(90));
        assertFalse(WallpaperModes.isValidInterval(WallpaperModes.EACH_RESTART));
        assertFalse(WallpaperModes.isValidInterval(25));
    }
}
