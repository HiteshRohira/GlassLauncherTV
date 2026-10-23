package tv.glass.launcher;

/** Pure validation and stepping rules for user-selectable home/grid layout. */
final class LayoutOptions {
    static final int MIN_COLUMNS = 4;
    static final int MAX_COLUMNS = 7;
    static final int DEFAULT_COLUMNS = 5;

    static final int MIN_CORNER_PERCENT = 0;
    static final int MAX_CORNER_PERCENT = 30;
    static final int CORNER_STEP_PERCENT = 2;
    static final int DEFAULT_CORNER_PERCENT = 14;

    static final boolean DEFAULT_FOCUS_BORDER_ENABLED = false;
    static final int DEFAULT_FOCUS_COLOR = 0xFFFFFFFF;
    static final boolean DEFAULT_FAVORITES_BACKGROUND = true;
    private static final int FOCUS_COLOR_ORANGE = 0xFFFF9800;
    private static final int LEGACY_FOCUS_COLOR_ORANGE = 0xFFFF3D00;
    private static final int[] FOCUS_COLORS = {
            0xFFFFFFFF,
            0xFF00E5FF,
            0xFFFFD600,
            0xFF76FF03,
            0xFFFF4081,
            FOCUS_COLOR_ORANGE,
            0xFF2979FF,
            0xFFE040FB
    };
    private static final String[] FOCUS_COLOR_NAMES = {
            "White", "Cyan", "Yellow", "Lime",
            "Pink", "Orange", "Blue", "Magenta"
    };

    private LayoutOptions() { /* no instances */ }

    static int sanitizeColumns(int columns) {
        return Math.max(MIN_COLUMNS, Math.min(columns, MAX_COLUMNS));
    }

    static int sanitizeCornerPercent(int percent) {
        int clamped = Math.max(MIN_CORNER_PERCENT, Math.min(percent, MAX_CORNER_PERCENT));
        int stepped = Math.round(clamped / (float) CORNER_STEP_PERCENT)
                * CORNER_STEP_PERCENT;
        return Math.min(stepped, MAX_CORNER_PERCENT);
    }

    static int stepColumns(int columns, int direction) {
        int delta = Integer.compare(direction, 0);
        return sanitizeColumns(sanitizeColumns(columns) + delta);
    }

    static int stepCornerPercent(int percent, int direction) {
        int delta = Integer.compare(direction, 0) * CORNER_STEP_PERCENT;
        return sanitizeCornerPercent(sanitizeCornerPercent(percent) + delta);
    }

    static int sanitizeFocusColor(int color) {
        int opaque = 0xFF000000 | (color & 0x00FFFFFF);
        return opaque == LEGACY_FOCUS_COLOR_ORANGE ? FOCUS_COLOR_ORANGE : opaque;
    }

    private static int focusColorIndex(int color) {
        int current = sanitizeFocusColor(color);
        for (int i = 0; i < FOCUS_COLORS.length; i++) {
            if (FOCUS_COLORS[i] == current) return i;
        }
        return 0;
    }

    static int stepFocusColor(int color, int direction) {
        int index = focusColorIndex(color);
        int delta = direction < 0 ? -1 : 1;
        return FOCUS_COLORS[(index + delta + FOCUS_COLORS.length) % FOCUS_COLORS.length];
    }

    static String focusColorName(int color) {
        return FOCUS_COLOR_NAMES[focusColorIndex(color)];
    }
}
