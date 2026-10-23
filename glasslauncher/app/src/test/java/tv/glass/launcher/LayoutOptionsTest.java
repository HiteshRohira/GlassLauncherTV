package tv.glass.launcher;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LayoutOptionsTest {

    @Test public void defaultsPreserveCurrentLayout() {
        assertEquals(5, LayoutOptions.DEFAULT_COLUMNS);
        assertEquals(14, LayoutOptions.DEFAULT_CORNER_PERCENT);
    }

    @Test public void columnsClampAndStepAcrossFourToSeven() {
        assertEquals(4, LayoutOptions.sanitizeColumns(2));
        assertEquals(7, LayoutOptions.sanitizeColumns(9));
        assertEquals(5, LayoutOptions.stepColumns(4, 1));
        assertEquals(6, LayoutOptions.stepColumns(7, -1));
        assertEquals(4, LayoutOptions.stepColumns(4, -1));
        assertEquals(7, LayoutOptions.stepColumns(7, 1));
    }

    @Test public void cornerPercentClampsToEvenStepsAcrossZeroToThirty() {
        assertEquals(0, LayoutOptions.sanitizeCornerPercent(-1));
        assertEquals(16, LayoutOptions.sanitizeCornerPercent(15));
        assertEquals(30, LayoutOptions.sanitizeCornerPercent(31));
        assertEquals(18, LayoutOptions.stepCornerPercent(16, 1));
        assertEquals(14, LayoutOptions.stepCornerPercent(16, -1));
        assertEquals(0, LayoutOptions.stepCornerPercent(0, -1));
        assertEquals(30, LayoutOptions.stepCornerPercent(30, 1));
    }

    @Test public void focusBorderDefaultsOffWithWhiteHighContrastColor() {
        assertEquals(false, LayoutOptions.DEFAULT_FOCUS_BORDER_ENABLED);
        assertEquals(0xFFFFFFFF, LayoutOptions.DEFAULT_FOCUS_COLOR);
        assertEquals("White", LayoutOptions.focusColorName(
                LayoutOptions.DEFAULT_FOCUS_COLOR));
    }

    @Test public void focusColorIsOpaqueCyclesAndUsesReadableNames() {
        assertEquals(0xFF123456, LayoutOptions.sanitizeFocusColor(0x00123456));
        assertEquals(0xFFFF9800, LayoutOptions.sanitizeFocusColor(0xFFFF3D00));
        assertEquals(0xFF00E5FF,
                LayoutOptions.stepFocusColor(LayoutOptions.DEFAULT_FOCUS_COLOR, 1));
        assertEquals(0xFFE040FB,
                LayoutOptions.stepFocusColor(LayoutOptions.DEFAULT_FOCUS_COLOR, -1));
        assertEquals(0xFFFF9800,
                LayoutOptions.stepFocusColor(0xFFFF4081, 1));
        assertEquals("Cyan", LayoutOptions.focusColorName(0xFF00E5FF));
        assertEquals("Orange", LayoutOptions.focusColorName(0xFFFF9800));
        assertEquals("Orange", LayoutOptions.focusColorName(0xFFFF3D00));
        assertEquals("Magenta", LayoutOptions.focusColorName(0xFFE040FB));
        assertEquals("White", LayoutOptions.focusColorName(0xFF123456));
    }
}
