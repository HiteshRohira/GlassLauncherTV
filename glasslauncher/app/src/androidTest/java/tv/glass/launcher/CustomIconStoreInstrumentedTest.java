package tv.glass.launcher;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;

/** Device-level regression coverage for custom-icon bitmap normalization. */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class CustomIconStoreInstrumentedTest {

    @Test
    public void normalizeForDisplay_landscapeFullBleed_preservesAspectRatio() {
        Bitmap source = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888);
        source.eraseColor(Color.RED);
        Bitmap normalized = null;
        try {
            normalized = CustomIconStore.normalizeForDisplay(source, 100);

            assertNotNull(normalized);
            assertEquals(100, normalized.getWidth());
            assertEquals(56, normalized.getHeight());
            assertEquals(Color.RED, normalized.getPixel(0, 0));
            assertEquals(Color.RED,
                    normalized.getPixel(normalized.getWidth() - 1,
                            normalized.getHeight() - 1));
        } finally {
            recycleIfDistinct(normalized, source);
            source.recycle();
        }
    }

    @Test
    public void normalizeForDisplay_transparentPadding_trimsWithoutSquaring() {
        Bitmap source = Bitmap.createBitmap(120, 100, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(source);
        Paint paint = new Paint();
        paint.setColor(Color.BLUE);
        canvas.drawRect(20, 40, 100, 60, paint);
        Bitmap normalized = null;
        try {
            normalized = CustomIconStore.normalizeForDisplay(source, 100);

            assertNotNull(normalized);
            assertEquals(80, normalized.getWidth());
            assertEquals(20, normalized.getHeight());
            int[] bounds = visibleBounds(normalized);
            assertEquals(0, bounds[0]);
            assertEquals(0, bounds[1]);
            assertEquals(normalized.getWidth(), bounds[2]);
            assertEquals(normalized.getHeight(), bounds[3]);
        } finally {
            recycleIfDistinct(normalized, source);
            source.recycle();
        }
    }

    @Test
    public void normalizeForDisplay_fullyTransparent_rejectsImage() {
        Bitmap source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        try {
            assertNull(CustomIconStore.normalizeForDisplay(source, 100));
        } finally {
            source.recycle();
        }
    }

    @Test
    public void generateCustomTile_opaqueArtwork_fillsTileInsteadOfCenteredSquare() {
        Bitmap source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        source.eraseColor(Color.RED);
        Bitmap tile = null;
        try {
            tile = IconRenderer.generateCustomTile(source, 300, 180, 12);

            assertNotNull(tile);
            assertColorNear(Color.RED, tile.getPixel(10, 90));
            assertColorNear(Color.RED, tile.getPixel(290, 90));
            assertColorNear(Color.RED, tile.getPixel(150, 10));
            assertColorNear(Color.RED, tile.getPixel(150, 170));
        } finally {
            if (tile != null && !tile.isRecycled()) tile.recycle();
            source.recycle();
        }
    }

    @Test
    public void generateCustomTile_transparentLogo_preservesContourAndUsesTileHeight() {
        Bitmap source = transparentCircleSource();
        Bitmap normalized = null;
        Bitmap tile = null;
        try {
            normalized = CustomIconStore.normalizeForDisplay(source, 100);
            assertNotNull(normalized);
            tile = IconRenderer.generateCustomTile(normalized, 300, 180, 12);

            assertNotNull(tile);
            int[] redBounds = redBounds(tile);
            assertTrue(redBounds[2] - redBounds[0] >= 150);
            assertTrue(redBounds[3] - redBounds[1] >= 150);
            assertColorNear(Color.RED, tile.getPixel(150, 90));
            assertFalse(isRed(tile.getPixel(70, 30)));
        } finally {
            if (tile != null && !tile.isRecycled()) tile.recycle();
            recycleIfDistinct(normalized, source);
            source.recycle();
        }
    }

    @Test
    public void processCustomIcon_transparentLogo_addsNoSquarePlate() {
        Bitmap source = transparentCircleSource();
        Bitmap normalized = null;
        Bitmap icon = null;
        try {
            normalized = CustomIconStore.normalizeForDisplay(source, 100);
            assertNotNull(normalized);
            icon = IconRenderer.processCustomIcon(normalized, 100);

            assertNotNull(icon);
            assertEquals(0, Color.alpha(icon.getPixel(0, 0)));
            assertEquals(0, Color.alpha(icon.getPixel(99, 99)));
            assertColorNear(Color.RED, icon.getPixel(50, 50));
        } finally {
            if (icon != null && !icon.isRecycled()) icon.recycle();
            recycleIfDistinct(normalized, source);
            source.recycle();
        }
    }

    @Test
    public void tvInputIdentity_saveReadDelete_roundTrips() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        String identity = "tvinput://test.oem/.HdmiInputService/HW99";
        CustomIconStore store = new CustomIconStore(context);
        File sourceFile = new File(context.getCacheDir(), "tv-input-icon-source.png");
        Bitmap source = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        source.eraseColor(Color.MAGENTA);
        try {
            try (FileOutputStream out = new FileOutputStream(sourceFile)) {
                assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, out));
            }
            store.delete(identity);

            assertTrue(store.save(context.getContentResolver(),
                    Uri.fromFile(sourceFile), identity));
            assertTrue(store.has(identity));
            Bitmap stored = store.read(identity);
            try {
                assertNotNull(stored);
                int pixel = stored.getPixel(0, 0);
                assertTrue(Math.abs(Color.red(pixel) - Color.red(Color.MAGENTA)) <= 2);
                assertTrue(Math.abs(Color.green(pixel) - Color.green(Color.MAGENTA)) <= 2);
                assertTrue(Math.abs(Color.blue(pixel) - Color.blue(Color.MAGENTA)) <= 2);
            } finally {
                if (stored != null && !stored.isRecycled()) stored.recycle();
            }
            assertTrue(store.delete(identity));
            assertFalse(store.has(identity));
        } finally {
            store.delete(identity);
            source.recycle();
            //noinspection ResultOfMethodCallIgnored
            sourceFile.delete();
        }
    }

    private static Bitmap transparentCircleSource() {
        Bitmap source = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.RED);
        new Canvas(source).drawCircle(50, 50, 25, paint);
        return source;
    }

    private static int[] redBounds(Bitmap bitmap) {
        int left = bitmap.getWidth();
        int top = bitmap.getHeight();
        int right = -1;
        int bottom = -1;
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                if (!isRed(bitmap.getPixel(x, y))) continue;
                left = Math.min(left, x);
                top = Math.min(top, y);
                right = Math.max(right, x);
                bottom = Math.max(bottom, y);
            }
        }
        return new int[] {left, top, right + 1, bottom + 1};
    }

    private static boolean isRed(int color) {
        return Color.alpha(color) > 200
                && Color.red(color) > 200
                && Color.green(color) < 40
                && Color.blue(color) < 40;
    }

    private static void assertColorNear(int expected, int actual) {
        assertTrue(Math.abs(Color.red(expected) - Color.red(actual)) <= 2);
        assertTrue(Math.abs(Color.green(expected) - Color.green(actual)) <= 2);
        assertTrue(Math.abs(Color.blue(expected) - Color.blue(actual)) <= 2);
        assertTrue(Math.abs(Color.alpha(expected) - Color.alpha(actual)) <= 2);
    }

    private static int[] visibleBounds(Bitmap bitmap) {
        int left = bitmap.getWidth();
        int top = bitmap.getHeight();
        int right = -1;
        int bottom = -1;
        for (int y = 0; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                if (Color.alpha(bitmap.getPixel(x, y)) == 0) continue;
                left = Math.min(left, x);
                top = Math.min(top, y);
                right = Math.max(right, x);
                bottom = Math.max(bottom, y);
            }
        }
        return new int[] {left, top, right + 1, bottom + 1};
    }

    private static void recycleIfDistinct(Bitmap candidate, Bitmap source) {
        if (candidate != null && candidate != source && !candidate.isRecycled()) {
            candidate.recycle();
        }
    }
}
