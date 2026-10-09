package tv.glass.launcher;

/* loaded from: classes2.dex */
final class At4kHomeLayout {
    private static final float BOTTOM_PADDING_FRACTION = 0.06f;
    static final int COLUMNS = 6;
    private static final float COLUMN_GAP_FRACTION = 0.0229f;
    private static final float FAVORITES_PLATE_MARGIN_FRACTION = 0.033f;
    private static final float FAVORITES_PLATE_PADDING_FRACTION = 0.038f;
    private static final float GRID_HORIZONTAL_MARGIN_FRACTION = 0.05625f;
    private static final float GRID_TOP_FRACTION = 0.205f;
    private static final float LABEL_AREA_FRACTION = 0.04f;
    private static final float ROW_GAP_FRACTION = 0.092f;

    private At4kHomeLayout() {
    }

    static Metrics calculate(int screenWidthPx, int screenHeightPx, float density) {
        return calculate(screenWidthPx, screenHeightPx, density,
                LayoutOptions.DEFAULT_COLUMNS, LayoutOptions.DEFAULT_CORNER_PERCENT);
    }

    static Metrics calculate(int screenWidthPx, int screenHeightPx, float density,
                             int columns, int cornerPercent) {
        if (screenWidthPx <= 0) {
            throw new IllegalArgumentException("screenWidthPx must be positive");
        }
        if (screenHeightPx <= 0) {
            throw new IllegalArgumentException("screenHeightPx must be positive");
        }
        if (density <= 0.0f || !Float.isFinite(density)) {
            throw new IllegalArgumentException("density must be finite and positive");
        }
        columns = LayoutOptions.sanitizeColumns(columns);
        int gridHorizontalMarginPx = Math.max(dp(24, density), Math.round(screenWidthPx * GRID_HORIZONTAL_MARGIN_FRACTION));
        int columnGapPx = Math.max(dp(12, density), Math.round(screenWidthPx * COLUMN_GAP_FRACTION));
        int tileWidthPx = Math.max(dp(64, density), ((screenWidthPx - (gridHorizontalMarginPx * 2)) - (columnGapPx * (columns - 1))) / columns);
        int tileHeightPx = tileHeightPx(tileWidthPx);
        int labelAreaPx = Math.max(dp(28, density), Math.round(screenHeightPx * LABEL_AREA_FRACTION));
        return new Metrics(gridHorizontalMarginPx, columnGapPx, tileWidthPx, tileHeightPx, cornerRadiusPx(tileHeightPx, cornerPercent), tileHeightPx + labelAreaPx, Math.max(dp(72, density), Math.round(screenHeightPx * GRID_TOP_FRACTION)), Math.max(dp(24, density), Math.round(screenHeightPx * ROW_GAP_FRACTION)), Math.max(dp(24, density), Math.round(screenWidthPx * FAVORITES_PLATE_MARGIN_FRACTION)), Math.max(dp(12, density), Math.round(screenHeightPx * FAVORITES_PLATE_PADDING_FRACTION)), Math.max(dp(48, density), Math.round(screenHeightPx * BOTTOM_PADDING_FRACTION)));
    }

    static int tileHeightPx(int tileWidthPx) {
        return Math.round(Math.max(0, tileWidthPx) * 3.0f / 5.0f);
    }

    static int cornerRadiusPx(int tileHeightPx, int cornerPercent) {
        return Math.round(Math.max(0, tileHeightPx)
                * LayoutOptions.sanitizeCornerPercent(cornerPercent) / 100f);
    }

    static boolean shouldBlurBackground(int focusedIndex, int favoritesCount) {
        return focusedIndex >= Math.max(0, favoritesCount);
    }

    static boolean shouldShowLabel(int focusedIndex, int favoritesCount) {
        return focusedIndex >= Math.max(0, favoritesCount);
    }

    static int centeredTop(int containerHeightPx, int contentHeightPx) {
        if (containerHeightPx < 0 || contentHeightPx < 0) {
            throw new IllegalArgumentException("heights must be non-negative");
        }
        return Math.max(0, (containerHeightPx - contentHeightPx) / 2);
    }

    static int favoritesPlateTop(int firstRowTopPx, int scrollYPx) {
        return firstRowTopPx - scrollYPx;
    }

    static int favoritesPlateBottom(int firstRowTopPx, int scrollYPx,
                                    int cellHeightPx) {
        return favoritesPlateTop(firstRowTopPx, scrollYPx)
                + Math.max(0, cellHeightPx);
    }

    private static int dp(int value, float density) {
        return Math.round(value * density);
    }

    static final class Metrics {
        final int bottomPaddingPx;
        final int cellHeightPx;
        final int columnGapPx;
        final int favoritesPlateHorizontalMarginPx;
        final int favoritesPlateVerticalPaddingPx;
        final int gridHorizontalMarginPx;
        final int gridTopPx;
        final int rowGapPx;
        final int tileCornerPx;
        final int tileHeightPx;
        final int tileWidthPx;

        Metrics(int gridHorizontalMarginPx, int columnGapPx, int tileWidthPx, int tileHeightPx, int tileCornerPx, int cellHeightPx, int gridTopPx, int rowGapPx, int favoritesPlateHorizontalMarginPx, int favoritesPlateVerticalPaddingPx, int bottomPaddingPx) {
            this.gridHorizontalMarginPx = gridHorizontalMarginPx;
            this.columnGapPx = columnGapPx;
            this.tileWidthPx = tileWidthPx;
            this.tileHeightPx = tileHeightPx;
            this.tileCornerPx = tileCornerPx;
            this.cellHeightPx = cellHeightPx;
            this.gridTopPx = gridTopPx;
            this.rowGapPx = rowGapPx;
            this.favoritesPlateHorizontalMarginPx = favoritesPlateHorizontalMarginPx;
            this.favoritesPlateVerticalPaddingPx = favoritesPlateVerticalPaddingPx;
            this.bottomPaddingPx = bottomPaddingPx;
        }
    }
}
