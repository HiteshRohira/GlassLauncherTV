package tv.glass.launcher;

import io.github.jqssun.airplay.renderer.MirrorPresentationClock;
import io.github.jqssun.airplay.renderer.RenderedFrameStats;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public class MirrorPresentationClockTest {
    private static final long MS = 1_000_000L;

    @Test public void sevenJitteryReceiveRunsKeepSteadySenderCadence() {
        for (int run=0;run<7;run++) {
            Random random=new Random(run);
            MirrorPresentationClock clock=new MirrorPresentationClock();
            RenderedFrameStats rendered=new RenderedFrameStats();
            for(int frame=0;frame<3600;frame++) {
                long pts=1_700_000_000_000_000L+frame*16_667L;
                long arrival=10_000*MS+frame*16_667_000L+(random.nextInt(13)-6)*MS;
                long presentation=clock.releaseTimeNs(pts,arrival);
                assertTrue(presentation>=arrival);
                assertTrue(presentation-arrival<=100*MS);
                rendered.record(pts,presentation);
            }
            assertEquals("receive jitter is buffered",0,rendered.intervalErrorP95Us());
            assertEquals(0,clock.getResyncs());
        }
    }
    @Test public void variableSenderCadenceIsPreservedAndMeasuredSeparately() {
        MirrorPresentationClock clock=new MirrorPresentationClock();
        RenderedFrameStats stats=new RenderedFrameStats();
        long pts=0,now=1000*MS;
        for(int frame=0;frame<300;frame++) {
            long step=frame%2==0?16_667:33_333;
            pts+=step;now+=step*1000;
            stats.record(pts,clock.releaseTimeNs(pts,now));
        }
        assertTrue("raw variation includes source cadence",stats.jitterUs()>8000);
        assertEquals("receiver added no interval error",0,stats.intervalErrorP95Us());
    }
    @Test public void lateDecodeAndTimestampJumpsCannotBuildUnboundedDelay() {
        MirrorPresentationClock clock=new MirrorPresentationClock();
        clock.releaseTimeNs(1_000_000,1000*MS);
        long late=clock.releaseTimeNs(1_016_667,1500*MS);
        assertTrue(late>=1500*MS && late<=1600*MS);
        long jump=clock.releaseTimeNs(Long.MAX_VALUE,1520*MS);
        assertTrue(jump>=1520*MS && jump<=1620*MS);
        long backwards=clock.releaseTimeNs(123,1540*MS);
        assertTrue(backwards>=1540*MS && backwards<=1640*MS);
        assertTrue(clock.getResyncs()>=3);
    }
    @Test public void decoderStartupBacklogDoesNotBecomePermanentLatency() {
        MirrorPresentationClock clock=new MirrorPresentationClock();
        for(int frame=0;frame<60;frame++) {
            long now=1000*MS+frame*MS;
            long target=clock.releaseTimeNs(frame*16_667L,now);
            assertTrue("catch up buffered startup frames",target-now<=34*MS);
        }
    }
    @Test public void aLatePacketDoesNotReanchorAllLaterFramesToItsDelay() {
        MirrorPresentationClock clock=new MirrorPresentationClock();
        clock.releaseTimeNs(0,1000*MS);
        clock.releaseTimeNs(16_667,1500*MS);
        assertEquals("late decode does not reset the clock",0,clock.getResyncs());
        assertEquals(1,clock.getLateOutputs());
        long recovered=clock.releaseTimeNs(500_000,1516*MS);
        assertTrue("return to earliest observed source timeline",recovered-1516*MS<20*MS);
    }
    @Test public void arrivalAndOutputCadenceSeparateBufferedNetworkVariation() {
        RenderedFrameStats arrivals=new RenderedFrameStats();
        RenderedFrameStats output=new RenderedFrameStats();
        MirrorPresentationClock clock=new MirrorPresentationClock();
        for(int i=0;i<600;i++) {
            long pts=i*16_667L;
            long now=1000*MS+pts*1000+(i%2==0?0:12*MS);
            arrivals.record(pts,now);
            output.record(pts,clock.releaseTimeNs(pts,now));
        }
        assertEquals(12000,arrivals.intervalErrorP95Us());
        assertEquals(0,output.intervalErrorP95Us());
        clock.reset();
        assertEquals(0,clock.getLateOutputs());
    }
    @Test public void longStillScreenDoesNotRequireAnotherFrameToShowItsUpdate() {
        MirrorPresentationClock clock=new MirrorPresentationClock();
        clock.releaseTimeNs(0,1000*MS);
        long next=clock.releaseTimeNs(60_000_000,61_000*MS);
        assertTrue(next>=61_000*MS && next<61_100*MS);
    }
    @Test public void renderedStatsUseTimestampsAndRetainOnlyTheRecentWindow() {
        RenderedFrameStats stats=new RenderedFrameStats();
        for(int i=0;i<1000;i++)stats.record(i*16_667L,i*16_667_000L+(i<100?50*MS:0));
        assertEquals(0,stats.jitterUs());
        assertEquals(0,stats.intervalErrorP95Us());
        assertEquals(1000,stats.getFrames());
        stats.reset();assertEquals(0,stats.getFrames());assertEquals(0,stats.intervalErrorP95Us());
    }
}
