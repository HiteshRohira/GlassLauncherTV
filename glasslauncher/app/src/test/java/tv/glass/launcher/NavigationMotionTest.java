package tv.glass.launcher;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class NavigationMotionTest {

    @Test
    public void surfaceOffsetAlignsTargetWithSource() {
        assertEquals(320f, NavigationMotion.surfaceOffsetY(900f, 580f, 1080), 0.001f);
        assertEquals(-240f, NavigationMotion.surfaceOffsetY(340f, 580f, 1080), 0.001f);
    }

    @Test
    public void surfaceOffsetIsBoundedAndRejectsInvalidGeometry() {
        assertEquals(550f, NavigationMotion.surfaceOffsetY(900f, 0f, 1000), 0.001f);
        assertEquals(-550f, NavigationMotion.surfaceOffsetY(0f, 900f, 1000), 0.001f);
        assertEquals(0f, NavigationMotion.surfaceOffsetY(Float.NaN, 400f, 1000), 0.001f);
        assertEquals(0f, NavigationMotion.surfaceOffsetY(700f, 400f, 0), 0.001f);
    }

    @Test
    public void motionDurationsStayBelowPerceivedLagThreshold() {
        assertEquals(220, NavigationMotion.SURFACE_DURATION_MS);
        assertEquals(120, NavigationMotion.GRID_FOCUS_DURATION_MS);
    }
}
