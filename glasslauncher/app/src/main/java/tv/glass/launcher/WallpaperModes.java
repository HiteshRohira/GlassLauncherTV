package tv.glass.launcher;

/**
 * Rules behind the Wallpaper settings page, kept free of Android so they can
 * be unit tested: which wallpaper source is active, and how the Source and
 * Change picture rows step through their values.
 */
final class WallpaperModes {

    private WallpaperModes() { /* no instances */ }

    static final int PICTURE = 0;
    static final int SLIDESHOW = 1;
    static final int SYSTEM = 2;
    private static final int SOURCE_COUNT = 3;

    /** Change picture value meaning a new picture each time the launcher starts. */
    static final int EACH_RESTART = -1;

    /** The values Change picture steps through: each restart, then intervals in seconds. */
    static final int[] CHANGE_STEPS = { EACH_RESTART, 20, 30, 45, 60, 90, 120, 180 };

    /** The active source. A folder whose slideshow never changes counts as a picture. */
    static int source(boolean systemWallpaper, boolean slideshowActive) {
        if (systemWallpaper) return SYSTEM;
        return slideshowActive ? SLIDESHOW : PICTURE;
    }

    /** Next source in Picture, Slideshow, System order, wrapping at both ends. */
    static int stepSource(int source, int direction) {
        return Math.floorMod(source + Integer.signum(direction), SOURCE_COUNT);
    }

    /** The Change picture value for the stored slideshow settings. */
    static int changeValue(int intervalSec, boolean eachRestart) {
        return eachRestart ? EACH_RESTART : intervalSec;
    }

    /** Step a Change picture value, wrapping at both ends. A value that is not
     *  in {@link #CHANGE_STEPS} (such as Off) lands on {@code fallbackSec}. */
    static int stepChange(int value, int direction, int fallbackSec) {
        int index = indexOf(value);
        if (index < 0) return indexOf(fallbackSec) >= 0 ? fallbackSec : CHANGE_STEPS[1];
        return CHANGE_STEPS[Math.floorMod(index + Integer.signum(direction), CHANGE_STEPS.length)];
    }

    /** Whether a stored interval is one the launcher offers. 0 means no timer. */
    static boolean isValidInterval(int intervalSec) {
        return intervalSec == 0 || indexOf(intervalSec) > 0;
    }

    private static int indexOf(int value) {
        for (int i = 0; i < CHANGE_STEPS.length; i++) if (CHANGE_STEPS[i] == value) return i;
        return -1;
    }
}
