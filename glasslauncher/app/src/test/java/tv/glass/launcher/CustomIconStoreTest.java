package tv.glass.launcher;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** JVM tests for the Android-free decisions inside {@link CustomIconStore}. */
public class CustomIconStoreTest {

    @Test public void computeSampleSize_imageWithinBound_returnsOne() {
        assertEquals(1, CustomIconStore.computeSampleSize(512, 512, 512));
        assertEquals(1, CustomIconStore.computeSampleSize(320, 480, 512));
    }

    @Test public void computeSampleSize_largeSquare_stopsAtTargetResolution() {
        assertEquals(8, CustomIconStore.computeSampleSize(4096, 4096, 512));
    }

    @Test public void computeSampleSize_panorama_boundsLongestAxis() {
        assertEquals(8, CustomIconStore.computeSampleSize(8000, 500, 512));
        assertEquals(8, CustomIconStore.computeSampleSize(500, 8000, 512));
    }

    @Test public void computeSampleSize_invalidDimensions_returnsOne() {
        assertEquals(1, CustomIconStore.computeSampleSize(0, 500, 512));
        assertEquals(1, CustomIconStore.computeSampleSize(500, 0, 512));
        assertEquals(1, CustomIconStore.computeSampleSize(500, 500, 0));
    }

    @Test public void computeSampleSize_pathologicalInput_terminatesAtCap() {
        assertEquals(0x4000,
                CustomIconStore.computeSampleSize(Integer.MAX_VALUE, Integer.MAX_VALUE, 1));
    }

    @Test public void scaledDimensions_landscape_preservesAspectRatio() {
        assertArrayEquals(new int[] {512, 288},
                CustomIconStore.scaledDimensions(1600, 900, 512));
    }

    @Test public void scaledDimensions_portrait_preservesAspectRatio() {
        assertArrayEquals(new int[] {288, 512},
                CustomIconStore.scaledDimensions(900, 1600, 512));
    }

    @Test public void scaledDimensions_smallImage_preservesOriginalSize() {
        assertArrayEquals(new int[] {320, 180},
                CustomIconStore.scaledDimensions(320, 180, 512));
    }

    @Test public void scaledDimensions_invalidDimensions_returnsNull() {
        assertNull(CustomIconStore.scaledDimensions(0, 512, 512));
        assertNull(CustomIconStore.scaledDimensions(512, 0, 512));
        assertNull(CustomIconStore.scaledDimensions(512, 512, 0));
    }

    @Test public void fileStemForIdentity_realPackage_preservesExistingFilename() {
        assertEquals("com.example.player",
                CustomIconStore.fileStemForIdentity("com.example.player"));
    }

    @Test public void fileStemForIdentity_tvInput_isStableFlatAndSafe() {
        String identity = "tvinput://com.oem/.HdmiInputService/HW5";
        String first = CustomIconStore.fileStemForIdentity(identity);
        String second = CustomIconStore.fileStemForIdentity(identity);

        assertEquals(first, second);
        assertTrue(first.startsWith("@tvinput_"));
        assertFalse(first.contains("/"));
        assertFalse(first.contains(":"));
    }

    @Test public void fileStemForIdentity_distinctInputs_doNotCollide() {
        String hdmiOne = CustomIconStore.fileStemForIdentity("tvinput://oem/HW1");
        String hdmiTwo = CustomIconStore.fileStemForIdentity("tvinput://oem/HW2");

        assertFalse(hdmiOne.equals(hdmiTwo));
    }

    @Test public void fileStemForIdentity_invalidSyntheticIdentity_returnsNull() {
        assertNull(CustomIconStore.fileStemForIdentity("tvinput://"));
        assertNull(CustomIconStore.fileStemForIdentity("../outside"));
    }

    @Test public void isSafePackageName_acceptsAndroidIdentifiers() {
        assertTrue(CustomIconStore.isSafePackageName("com.example.tv_launcher2"));
        assertTrue(CustomIconStore.isSafePackageName("org.videolan.vlc"));
    }

    @Test public void isSafePackageName_rejectsMissingAndSyntheticKeys() {
        assertFalse(CustomIconStore.isSafePackageName(null));
        assertFalse(CustomIconStore.isSafePackageName(""));
        assertFalse(CustomIconStore.isSafePackageName("tvinput://hdmi1"));
    }

    @Test public void isSafePackageName_rejectsPathTraversal() {
        assertFalse(CustomIconStore.isSafePackageName("../outside"));
        assertFalse(CustomIconStore.isSafePackageName("com.example/app"));
        assertFalse(CustomIconStore.isSafePackageName("com.example\\app"));
    }
}
