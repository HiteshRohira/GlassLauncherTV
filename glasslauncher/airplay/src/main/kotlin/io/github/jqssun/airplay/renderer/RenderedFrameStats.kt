package io.github.jqssun.airplay.renderer

import kotlin.math.abs
import kotlin.math.sqrt

/** Bounded output-surface timing, using MediaCodec's render timestamp rather
 * than callback delivery time (callbacks may be batched). Caller owns locking.
 */
class RenderedFrameStats {
    private val intervals = LongArray(120)
    private val errors = LongArray(120)
    private val estimatedDelays = LongArray(120)
    private var delayPosition = 0
    private var delayCount = 0
    private var position = 0
    private var count = 0
    private var lastPtsUs = Long.MIN_VALUE
    private var lastRenderNs = 0L
    var frames = 0L; private set
    var discontinuities = 0L; private set

    fun reset() {
        position = 0; count = 0; lastPtsUs = Long.MIN_VALUE; lastRenderNs = 0
        frames = 0; discontinuities = 0
        delayPosition = 0; delayCount = 0
    }

    fun record(ptsUs: Long, renderNs: Long) {
        frames++
        if (lastPtsUs != Long.MIN_VALUE) {
            val sourceUs = ptsUs - lastPtsUs
            val outputNs = renderNs - lastRenderNs
            if (sourceUs in 0..10_000_000L && outputNs > 0) {
                intervals[position] = outputNs
                errors[position] = abs(outputNs - sourceUs * 1000L)
                position = (position + 1) % intervals.size
                count = minOf(count + 1, intervals.size)
            } else discontinuities++
        }
        lastPtsUs = ptsUs; lastRenderNs = renderNs
    }

    /** Standard deviation of actual render intervals, including source cadence. */
    fun jitterUs(): Long {
        if (count < 2) return 0
        var sum = 0.0; var squares = 0.0
        for (i in 0 until count) { val v = intervals[i].toDouble(); sum += v; squares += v * v }
        return (sqrt((squares / count - (sum / count) * (sum / count)).coerceAtLeast(0.0)) / 1000).toLong()
    }

    /** Error relative to sender PTS cadence. Natural 30/60-fps changes are not
     * receiver jitter; this exposes presentation variation added by the TV. */
    fun intervalErrorP95Us(): Long {
        if (count == 0) return 0
        val sorted = errors.copyOf(count).also { it.sort() }
        return sorted[(kotlin.math.ceil(count * .95).toInt() - 1).coerceAtLeast(0)] / 1000
    }

    /** Approximate sender-PTS-to-output latency after native NTP mapping.
     * This is explicitly an estimate, not camera-observed screen latency. */
    fun recordEstimatedDelay(delayNs: Long) {
        estimatedDelays[delayPosition] = delayNs
        delayPosition = (delayPosition + 1) % estimatedDelays.size
        delayCount = minOf(delayCount + 1, estimatedDelays.size)
    }

    fun estimatedDelayUs(q: Double): Long? {
        if (delayCount == 0) return null
        val sorted = estimatedDelays.copyOf(delayCount).also { it.sort() }
        return sorted[(kotlin.math.ceil(delayCount * q).toInt() - 1).coerceIn(0, delayCount - 1)] / 1000
    }
}
