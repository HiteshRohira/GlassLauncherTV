package io.github.jqssun.airplay.renderer

/**
 * Reusable byte arrays for compressed mirror frames. Native code used to
 * allocate a new Java array for every frame (60 a second), then the decoder
 * copied it into its own buffer, so the receiver process churned the
 * garbage collector. Arrays come in 32 KiB steps; at most [maxPooled] are
 * kept and very large frames are never pooled. Thread-safe.
 */
class FrameBufferPool @JvmOverloads constructor(private val maxPooled: Int = 24, private val maxPooledBytes: Int = 2 * 1024 * 1024) {
    private val free = ArrayList<ByteArray>()
    private var freeBytes = 0
    private val totalBudget = 4 * 1024 * 1024

    /** An array of at least [size] bytes; contents are undefined. */
    @Synchronized fun obtain(size: Int): ByteArray {
        var best = -1
        for (i in free.indices) {
            val a = free[i]
            if (a.size >= size && a.size <= maxOf(roundUp(size).toLong() * 4, STEP.toLong()) && (best < 0 || a.size < free[best].size)) best = i
        }
        if (best >= 0) return free.removeAt(best).also { freeBytes -= it.size }
        return ByteArray(roundUp(size))
    }

    /** Return an array obtained from [obtain]. Never use it afterwards. */
    @Synchronized fun recycle(a: ByteArray) {
        if (a.size > maxPooledBytes) return
        if (free.any { it === a }) return          // defensive: double recycle
        while (free.isNotEmpty() && (free.size >= maxPooled || freeBytes + a.size > totalBudget))
            freeBytes -= free.removeAt(0).size
        if (maxPooled <= 0 || a.size > totalBudget) return
        free.add(a); freeBytes += a.size
    }

    @Synchronized fun pooled(): Int = free.size
    @Synchronized fun retainedBytes(): Int = freeBytes
    @Synchronized fun trim() { free.clear(); freeBytes = 0 }

    companion object {
        private const val STEP = 32 * 1024
        fun roundUp(size: Int): Int = ((maxOf(size, 1) + STEP - 1) / STEP) * STEP
    }
}
