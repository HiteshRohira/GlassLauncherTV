package tv.glass.launcher;

import android.graphics.Bitmap;

/** Repeated box blur for the legacy grid preview. Three passes approximate
 *  a Gaussian kernel after the caller downsamples the wallpaper. */
final class LegacyGridBlur {
    private LegacyGridBlur() {}

    static Bitmap apply(Bitmap bitmap, int radius, int passes) {
        if (bitmap == null || radius <= 0 || passes <= 0) return bitmap;
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        blurArgb(pixels, width, height, radius, passes);
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }

    static void blurArgb(int[] pixels, int width, int height, int radius) {
        blurArgb(pixels, width, height, radius, 1);
    }

    static void blurArgb(int[] pixels, int width, int height, int radius, int passes) {
        if (passes <= 0) return;
        for (int pass = 0; pass < passes; pass++) {
            blurSinglePass(pixels, width, height, radius);
        }
    }

    private static void blurSinglePass(int[] pixels, int width, int height, int radius) {
        if (pixels == null || width <= 0 || height <= 0
                || pixels.length < width * height || radius <= 0) return;

        int[] horizontal = new int[width * height];
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int from = Math.max(0, x - radius);
                int to = Math.min(width - 1, x + radius);
                horizontal[row + x] = average(pixels, row + from, row + to, 1);
            }
        }

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int from = Math.max(0, y - radius);
                int to = Math.min(height - 1, y + radius);
                pixels[y * width + x] = average(horizontal, from * width + x,
                        to * width + x, width);
            }
        }
    }

    private static int average(int[] pixels, int from, int to, int step) {
        long a = 0, r = 0, g = 0, b = 0;
        int count = 0;
        for (int i = from; i <= to; i += step) {
            int color = pixels[i];
            a += color >>> 24;
            r += (color >>> 16) & 0xff;
            g += (color >>> 8) & 0xff;
            b += color & 0xff;
            count++;
        }
        return ((int) (a / count) << 24)
                | ((int) (r / count) << 16)
                | ((int) (g / count) << 8)
                | (int) (b / count);
    }
}
