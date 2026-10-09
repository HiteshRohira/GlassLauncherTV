package tv.glass.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LegacyGridBlurTest {

    @Test
    public void blurArgbSpreadsBrightPixelAcrossNeighbours() {
        int[] pixels = {0xff000000, 0xffffffff, 0xff000000};

        LegacyGridBlur.blurArgb(pixels, 3, 1, 1);

        assertEquals(0xff7f7f7f, pixels[0]);
        assertEquals(0xff555555, pixels[1]);
        assertEquals(0xff7f7f7f, pixels[2]);
    }

    @Test
    public void threePassBlurDiffusesBeyondSinglePassKernel() {
        int[] pixels = new int[9];
        for (int i = 0; i < pixels.length; i++) pixels[i] = 0xff000000;
        pixels[4] = 0xffffffff;

        LegacyGridBlur.blurArgb(pixels, 9, 1, 1, 3);

        assertTrue("third pass reaches three pixels from the source",
                (pixels[1] & 0xff) > 0);
        assertTrue("diffusion remains symmetric", pixels[1] == pixels[7]);
    }

    @Test
    public void blurArgbLeavesInputUnchangedForZeroRadius() {
        int[] pixels = {0xff123456};

        LegacyGridBlur.blurArgb(pixels, 1, 1, 0);

        assertEquals(0xff123456, pixels[0]);
    }
}
