package tv.glass.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.jqssun.airplay.renderer.MirrorFrameQueue;
import org.junit.Test;

/** The mirror decoder's input queue: bounded by latency, shedding cleanly. */
public class MirrorFrameQueueTest {
    private static final long MS = 1_000_000L;

    /** H.264 access unit: start code + NAL header (nal_ref_idc, type) + payload. */
    private static byte[] nal(int refIdc, int type, int size) {
        byte[] b = new byte[Math.max(size, 6)];
        b[3] = 1;
        b[4] = (byte) ((refIdc << 5) | type);
        return b;
    }
    private static byte[] idr() { return concat(nal(3, 7, 8), nal(3, 8, 6), nal(3, 5, 200)); }
    private static byte[] p() { return nal(2, 1, 100); }
    private static byte[] b() { return nal(0, 1, 100); }
    private static byte[] concat(byte[]... parts) {
        int n = 0; for (byte[] x : parts) n += x.length;
        byte[] out = new byte[n]; int o = 0;
        for (byte[] x : parts) { System.arraycopy(x, 0, out, o, x.length); o += x.length; }
        return out;
    }

    @Test public void detectsIdrAndReferenceFrames() {
        assertTrue(MirrorFrameQueue.isIdr(idr(), false));
        assertFalse("SPS alone is not a start point", MirrorFrameQueue.isIdr(nal(3, 7, 8), false));
        assertFalse(MirrorFrameQueue.isIdr(p(), false));
        assertTrue(MirrorFrameQueue.isReference(p(), false));
        assertFalse(MirrorFrameQueue.isReference(b(), false));
        byte[] hevcIdr = new byte[8]; hevcIdr[3] = 1; hevcIdr[4] = (byte) (19 << 1);
        assertTrue(MirrorFrameQueue.isIdr(hevcIdr, true));
    }

    @Test public void keepsEverythingWhileTheDecoderKeepsUp() {
        MirrorFrameQueue q = new MirrorFrameQueue();
        long t = 0;
        q.add(idr(), 0, t, false);
        for (int i = 1; i < 10; i++) q.add(p(), i, t += 16 * MS, false);
        assertEquals(10, q.getSize());
        assertEquals(0, q.getDropped());
    }

    @Test public void skipsToTheNewestIdrWhenBehind() {
        MirrorFrameQueue q = new MirrorFrameQueue();
        long t = 0;
        q.add(idr(), 0, t, false);
        for (int i = 1; i < 10; i++) q.add(p(), i, t += 20 * MS, false);
        q.add(idr(), 10, t += 20 * MS, false);
        q.add(p(), 11, t += 100 * MS, false);   // oldest is now 300 ms old
        assertEquals(1, q.getOverloads());
        MirrorFrameQueue.Frame first = q.poll();
        assertTrue("queue restarts at the newest IDR", first.getIdr());
        assertEquals(10, first.getPtsUs());
    }

    @Test public void overCountDropsNonReferenceFramesFirst() {
        MirrorFrameQueue q = new MirrorFrameQueue(Long.MAX_VALUE, Long.MAX_VALUE, 10);
        for (int i = 0; i < 16; i++) q.add(i % 2 == 0 ? p() : b(), i, i, false);
        assertEquals(10, q.getSize());
        int references = 0;
        for (MirrorFrameQueue.Frame f; (f = q.poll()) != null; ) if (f.getReference()) references++;
        assertEquals("every reference frame survives while non-reference ones can go", 8, references);
    }

    @Test public void overAgeDropsFromTheFront() {
        MirrorFrameQueue q = new MirrorFrameQueue();
        long t = 0;
        for (int i = 0; i < 20; i++) q.add(i % 2 == 0 ? p() : b(), i, t += 20 * MS, false);
        MirrorFrameQueue.Frame oldest = q.poll();
        assertTrue(t - oldest.getArrivedNs() <= 250 * MS);
    }

    @Test public void boundsLatencyEvenWithOnlyReferenceFrames() {
        MirrorFrameQueue q = new MirrorFrameQueue();
        long t = 0;
        for (int i = 0; i < 200; i++) q.add(p(), i, t += 16 * MS, false);
        MirrorFrameQueue.Frame oldest = q.poll();
        assertTrue("oldest queued frame must be under 250 ms old, was " + (t - oldest.getArrivedNs()) / MS + " ms",
                t - oldest.getArrivedNs() <= 250 * MS);
        assertTrue(q.getDropped() > 0);
    }

    @Test public void boundsBytes() {
        MirrorFrameQueue q = new MirrorFrameQueue();
        for (int i = 0; i < 20; i++) q.add(nal(2, 1, 1024 * 1024), i, i, false);
        assertTrue(q.getBytes() <= 6L * 1024 * 1024);
    }

    @Test public void pollReturnsFramesInOrderAndTracksBytes() {
        MirrorFrameQueue q = new MirrorFrameQueue();
        q.add(idr(), 1, 0, false);
        q.add(p(), 2, 1, false);
        assertEquals(1, q.poll().getPtsUs());
        assertEquals(2, q.poll().getPtsUs());
        assertEquals(0, q.getBytes());
        assertEquals(null, q.poll());
    }
}
