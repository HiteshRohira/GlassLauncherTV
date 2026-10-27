package tv.glass.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.github.jqssun.airplay.renderer.FrameBufferPool;
import io.github.jqssun.airplay.renderer.MirrorFrameQueue;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Pooled mirror frame arrays: reuse, bounds, and the queue handing back what it drops. */
public class FrameBufferPoolTest {
    @Test public void reusesArraysAndRoundsSizes() {
        FrameBufferPool pool = new FrameBufferPool();
        byte[] a = pool.obtain(1000);
        assertEquals(32 * 1024, a.length);
        pool.recycle(a);
        assertSame("same size class is reused", a, pool.obtain(5000));
        byte[] big = pool.obtain(100 * 1024);
        assertTrue(big.length >= 100 * 1024);
        pool.recycle(big);
        assertSame("a large free array serves a small request", big, pool.obtain(10));
    }

    @Test public void isBoundedAndIgnoresDoubleRecycle() {
        FrameBufferPool pool = new FrameBufferPool(3, 2 * 1024 * 1024);
        List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < 6; i++) out.add(pool.obtain(1000));
        for (byte[] b : out) pool.recycle(b);
        assertEquals(3, pool.pooled());
        byte[] x = pool.obtain(10);
        pool.recycle(x);
        pool.recycle(x);
        assertEquals(3, pool.pooled());
        pool.recycle(new byte[4 * 1024 * 1024]);   // too large to keep
        assertEquals(3, pool.pooled());
    }

    @Test public void retainedStorageHasAnAggregateByteBudget() {
        FrameBufferPool pool=new FrameBufferPool();
        List<byte[]> arrays=new ArrayList<>();
        for(int i=0;i<24;i++)arrays.add(pool.obtain(2*1024*1024));
        for(byte[] a:arrays)pool.recycle(a);
        assertTrue(pool.retainedBytes()<=4*1024*1024);
        assertTrue(pool.obtain(10).length<2*1024*1024);
        pool.trim();assertEquals(0,pool.retainedBytes());
    }

    @Test public void queueReturnsDroppedAndClearedFramesToThePool() {
        List<byte[]> released = new ArrayList<>();
        MirrorFrameQueue q = new MirrorFrameQueue(Long.MAX_VALUE, Long.MAX_VALUE, 2, b -> { released.add(b); return kotlin.Unit.INSTANCE; });
        byte[] f1 = new byte[64], f2 = new byte[64], f3 = new byte[64];
        f1[3] = 1; f1[4] = 0x41; f2[3] = 1; f2[4] = 0x41; f3[3] = 1; f3[4] = 0x41;   // P frames
        q.add(f1, 10, 1, 0, false);
        q.add(f2, 10, 2, 0, false);
        q.add(f3, 10, 3, 0, false);   // over count: oldest goes back
        assertEquals(1, released.size());
        assertSame(f1, released.get(0));
        assertEquals(20, q.getBytes());   // lengths, not array sizes
        MirrorFrameQueue.Frame polled = q.poll();
        assertSame(f2, polled.getData());
        q.clear();
        assertEquals(2, released.size());
        assertSame(f3, released.get(1));
        assertNotSame(f2, released.get(1));
    }
}
