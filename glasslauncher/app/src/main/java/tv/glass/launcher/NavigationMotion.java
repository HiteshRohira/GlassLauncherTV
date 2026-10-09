package tv.glass.launcher;

/** Pure geometry and timing for lightweight launcher navigation motion. */
final class NavigationMotion {

    static final int SURFACE_DURATION_MS = 220;
    static final int GRID_FOCUS_DURATION_MS = 120;

    private static final float MAX_SURFACE_TRAVEL_FRACTION = 0.55f;

    private NavigationMotion() {}

    /**
     * Returns the translation that visually aligns a resting target with the
     * source center. Travel is bounded so malformed geometry cannot move the
     * full-screen drawer completely outside the viewport.
     */
    static float surfaceOffsetY(float sourceCenterY, float targetCenterY,
                                int viewportHeight) {
        if (!Float.isFinite(sourceCenterY) || !Float.isFinite(targetCenterY)
                || viewportHeight <= 0) {
            return 0f;
        }
        float limit = viewportHeight * MAX_SURFACE_TRAVEL_FRACTION;
        return clamp(sourceCenterY - targetCenterY, -limit, limit);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(value, max));
    }
}
