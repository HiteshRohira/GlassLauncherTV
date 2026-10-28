package io.github.jqssun.airplay.renderer

/** Bounded sender-cadence playout with a two-vsync jitter cushion.
 * The earliest recent output arrival anchors the timeline, so decoder startup
 * backlog or a late packet cannot permanently add its delay to every frame.
 * It never discards coded reference frames or resets the decoder/session.
 */
class MirrorPresentationClock @JvmOverloads constructor(
    private val leadNs: Long = 33_333_334L,
    private val maxFutureNs: Long = 100_000_000L,
    private val maxLateNs: Long = 50_000_000L,
) {
    private val offsets=LongArray(120)
    private var count=0
    private var position=0
    private var previousPtsUs=Long.MIN_VALUE
    var resyncs=0L; private set
    var lateOutputs=0L; private set

    fun reset(){count=0;position=0;previousPtsUs=Long.MIN_VALUE;resyncs=0;lateOutputs=0}

    fun releaseTimeNs(ptsUs:Long,nowNs:Long):Long {
        if(previousPtsUs!=Long.MIN_VALUE && (ptsUs<previousPtsUs || ptsUs-previousPtsUs>10_000_000L)){
            count=0;position=0;resyncs++
        }
        previousPtsUs=ptsUs
        val observed=try {Math.subtractExact(nowNs,Math.multiplyExact(ptsUs,1000L))}
        catch(_:ArithmeticException){count=0;position=0;resyncs++;return nowNs+minOf(leadNs,maxFutureNs)}
        offsets[position]=observed;position=(position+1)%offsets.size;count=minOf(count+1,offsets.size)
        var earliest=offsets[0]
        for(i in 1 until count)earliest=minOf(earliest,offsets[i])
        val target=ptsUs*1000L+earliest+leadNs
        // A late decoded frame is clamped, not a clock re-anchor. Keep these
        // observations separate so diagnostics do not imply a timestamp reset.
        if(target-nowNs < -maxLateNs)lateOutputs++
        return target.coerceIn(nowNs,nowNs+maxFutureNs)
    }
}
