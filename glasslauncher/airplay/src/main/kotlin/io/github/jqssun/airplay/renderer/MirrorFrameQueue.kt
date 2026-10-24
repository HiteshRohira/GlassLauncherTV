package io.github.jqssun.airplay.renderer

/**
 * Compressed mirror frames waiting for a free decoder input buffer, bounded by
 * age and bytes rather than count: a decoder slightly slower than the sender
 * used to build up to a second of delay. Not thread-safe; the renderer guards it.
 *
 * When over a limit it sheds frames, cleanest option first:
 * 1. skip to the newest queued IDR (nothing after it needs older frames);
 * 2. over the byte or count limit, drop H.264 non-reference frames
 *    (nothing depends on them);
 * 3. then drop from the front: free for non-reference frames, otherwise the
 *    picture may smear until the next IDR. Only this lowers the age of the
 *    oldest frame, i.e. the delay.
 * iOS sends IDRs rarely while mirroring, so waiting for one could freeze the
 * picture; the last resort is what the old count-only queue always did.
 */
class MirrorFrameQueue(
    private val maxAgeNs: Long = 250_000_000L,
    private val maxBytes: Long = 6L * 1024 * 1024,
    private val maxCount: Int = 60,
) {
    class Frame(val data: ByteArray, val ptsUs: Long, val arrivedNs: Long, val idr: Boolean, val reference: Boolean)

    private val frames = ArrayDeque<Frame>()
    var bytes = 0L; private set
    /** Times the queue went over a limit. */
    var overloads = 0L; private set
    /** Frames dropped to stay within the limits. */
    var dropped = 0L; private set
    val size get() = frames.size

    /** Queues a frame and sheds older ones if needed. */
    fun add(data: ByteArray, ptsUs: Long, nowNs: Long, isH265: Boolean) {
        val idr = isIdr(data, isH265)
        frames.addLast(Frame(data, ptsUs, nowNs, idr, idr || isReference(data, isH265)))
        bytes += data.size
        if (overloaded(nowNs)) shed(nowNs)
    }

    fun poll(): Frame? = frames.removeFirstOrNull()?.also { bytes -= it.data.size }

    fun clear() { frames.clear(); bytes = 0 }

    fun resetStats() { overloads = 0; dropped = 0 }

    private fun overloaded(now: Long): Boolean = tooOld(now) || tooBig()
    private fun tooOld(now: Long): Boolean = frames.firstOrNull()?.let { now - it.arrivedNs > maxAgeNs } ?: false
    private fun tooBig(): Boolean = bytes > maxBytes || frames.size > maxCount

    private fun shed(now: Long) {
        overloads++
        val lastIdr = frames.indexOfLast { it.idr }
        if (lastIdr > 0) {
            repeat(lastIdr) { dropFirst() }
            if (!overloaded(now)) return
        }
        val newest = frames.last()
        val it = frames.iterator()
        while (it.hasNext() && tooBig()) {
            val f = it.next()
            if (!f.reference && f !== newest) { it.remove(); bytes -= f.data.size; dropped++ }
        }
        while (frames.size > 1 && overloaded(now)) dropFirst()
    }

    private fun dropFirst() {
        val f = frames.removeFirst()
        bytes -= f.data.size
        dropped++
    }

    companion object {
        /** True for an IDR/IRAP picture (with UxPlay, SPS/PPS arrive prepended
         *  to it). A parameter set alone is not a decodable start point. */
        @JvmStatic fun isIdr(data: ByteArray, isH265: Boolean): Boolean {
            var found = false
            forEachNal(data) { header ->
                found = if (isH265) ((header shr 1) and 0x3F) in 16..21 else (header and 0x1F) == 5
                !found
            }
            return found
        }

        /** False only for an H.264 picture whose slices all have nal_ref_idc 0.
         *  HEVC is treated as always referenced. */
        @JvmStatic fun isReference(data: ByteArray, isH265: Boolean): Boolean {
            if (isH265) return true
            var slices = 0
            var referenced = false
            forEachNal(data) { header ->
                val type = header and 0x1F
                if (type in 1..5) { slices++; if ((header shr 5) and 0x3 != 0) referenced = true }
                !referenced
            }
            return referenced || slices == 0
        }

        /** Calls [visit] with each NAL header byte after a 4-byte start code while it returns true. */
        private inline fun forEachNal(data: ByteArray, visit: (Int) -> Boolean) {
            var i = 0
            while (i <= data.size - 5) {
                if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                    data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                    if (!visit(data[i + 4].toInt() and 0xFF)) return
                    i += 4
                } else i++
            }
        }
    }
}
