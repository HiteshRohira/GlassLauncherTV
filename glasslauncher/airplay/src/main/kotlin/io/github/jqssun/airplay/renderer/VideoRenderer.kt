package io.github.jqssun.airplay.renderer

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Surface
import io.github.jqssun.airplay.renderer.DecoderSelector.Companion.videoCaps

class VideoRenderer(ctx: Context) {

    private val lock = Object()
    private val pipeline = VideoPipeline()
    val selector = DecoderSelector(ctx)
    private var avcDecoder: MediaCodecInfo? = null
    private var hevcDecoder: MediaCodecInfo? = null
    private var maxFps = 0
    private var codec: MediaCodec? = null
    private var displaySurface: Surface? = null
    private var currentH265 = false
    private var videoWidth = 0
    private var videoHeight = 0
    private var firstFrameQueued = false
    // Async codec state (guarded by lock). Frames from the network thread are
    // queued and submitted as input buffers free up, so the receive thread
    // never blocks; output is released the moment it decodes, so the last
    // frame of a still screen shows immediately instead of waiting for the next.
    private var codecThread: HandlerThread? = null
    private var codecHandler: Handler? = null
    private val freeInputs = ArrayDeque<Int>()
    private val pending = ArrayDeque<Pair<ByteArray, Long>>()
    /** True when the decoder renders straight into the display surface (no GL copy). */
    private var direct = false
    @Volatile private var _lastStatsLogMs = 0L

    // stats
    @Volatile var fps = 0; private set
    @Volatile var bitrateBps = 0L; private set
    @Volatile var frameCount = 0L; private set
    @Volatile var codecName = ""; private set
    @Volatile var droppedFrames = 0L; private set
    @Volatile var framePacingJitterUs = 0L; private set

    var enforceSdr = true
    var keyAllowFrameDrop = true
    var scheduledOutputBufferRelease = true
    var benchmarkLog = false
    var benchmarkLogCallback: ((String) -> Unit)? = null
    private var _framesThisSec = 0
    private var _bytesThisSec = 0L
    private var _lastStatReset = 0L
    private val _frameIntervalsNs = LongArray(120)
    private var _frameIntervalIdx = 0
    private var _frameIntervalCount = 0
    private var _lastOutputFrameNs = 0L
    // anchors that map decoder PTS (us) to System.nanoTime() for scheduled rendering
    private var _ptsBaseUs = Long.MIN_VALUE
    private var _wallBaseNs = 0L

    fun setResolution(w: Int, h: Int) = synchronized(lock) {
        if (w == videoWidth && h == videoHeight) return@synchronized
        videoWidth = w
        videoHeight = h
        pipeline.setVideoSize(w, h)
        stopCodec()
    }

    // Prefer decoding straight into the display surface; the GL pipeline is
    // only a holding surface while no display is attached. Switching uses
    // setOutputSurface, so the codec (and its reference frames) survives.
    fun setSurface(surface: Surface) = synchronized(lock) {
        if (displaySurface === surface) return@synchronized
        displaySurface = surface
        val c = codec
        if (c != null) {
            try {
                c.setOutputSurface(surface)
                direct = true
                pipeline.setDisplaySurface(null)
                return@synchronized
            } catch (e: Exception) {
                Log.w(TAG, "setOutputSurface to display failed; keeping GL pipeline", e)
            }
        }
        pipeline.setDisplaySurface(surface)
    }

    fun clearSurface(surface: Surface) = synchronized(lock) {
        if (displaySurface !== surface) return@synchronized
        displaySurface = null
        pipeline.setDisplaySurface(null)
        val c = codec ?: return@synchronized
        if (!direct) return@synchronized
        // The display is going away: park the decoder on the pipeline surface.
        try {
            pipeline.start()
            pipeline.setVideoSize(videoWidth, videoHeight)
            c.setOutputSurface(pipeline.inputSurface ?: error("no pipeline surface"))
            direct = false
        } catch (e: Exception) {
            Log.w(TAG, "could not park decoder; stopping", e)
            stopCodec()
        }
    }

    fun selectDecoders(w: Int, h: Int, fps: Int, h265: Boolean?): Boolean = synchronized(lock) {
        avcDecoder = selector.avc()
        hevcDecoder = if (h265 != false) selector.hevc(avcDecoder, w, h, fps, force = h265 == true) else null
        maxFps = fps
        Log.i(TAG, "decoders: avc=${avcDecoder?.name} hevc=${hevcDecoder?.name}")
        hevcDecoder != null
    }

    // unknown limits default to 1080p
    fun maxResolution(): Pair<Int, Int> =
        listOfNotNull(avcDecoder?.let { it to DecoderSelector.AVC }, hevcDecoder?.let { it to DecoderSelector.HEVC })
            .map { (info, mime) ->
                runCatching { info.videoCaps(mime).let { it.supportedWidths.upper to it.supportedHeights.upper } }
                    .getOrDefault(1920 to 1080)
            }
            .reduceOrNull { (w1, h1), (w2, h2) -> minOf(w1, w2) to minOf(h1, h2) } ?: (1920 to 1080)

    // codec per mirror session; pipeline persists across sessions
    fun startSession() = synchronized(lock) { _resetStats() }

    fun stopSession() = synchronized(lock) { stopCodec() }

    private fun _resetStats() {
        fps = 0; bitrateBps = 0; frameCount = 0; codecName = ""
        droppedFrames = 0; framePacingJitterUs = 0
        _framesThisSec = 0; _bytesThisSec = 0
    }

    private fun _updateStats(size: Int) {
        val now = System.currentTimeMillis()
        if (now - _lastStatReset >= 1000) {
            fps = _framesThisSec
            bitrateBps = _bytesThisSec * 8
            framePacingJitterUs = _computeFramePacingJitterUs()
            _framesThisSec = 0
            _bytesThisSec = 0
            _lastStatReset = now
            if (benchmarkLog) _emitBenchmarkLine()
            // Light always-on health line for diagnosing mirroring on the TV.
            if (now - _lastStatsLogMs >= 5000) {
                _lastStatsLogMs = now
                Log.i(TAG, "mirror in=${fps}fps out=${_outFrames - _outFramesAtLog}/5s " +
                    "kbps=${bitrateBps / 1000} pending=${pending.size} drops=$droppedFrames " +
                    "jitter=${framePacingJitterUs}us ${if (direct) "direct" else "gl"}")
                _outFramesAtLog = _outFrames
            }
        }
        _framesThisSec++
        _bytesThisSec += size
        frameCount++
    }

    private fun _emitBenchmarkLine() {
        val msg = "fps=$fps bitrate=${bitrateBps / 1000}kbps " +
            "jitter=${framePacingJitterUs}us frames=$frameCount " +
            "dropped=$droppedFrames codec=$codecName " +
            "res=${videoWidth}x${videoHeight}"
        Log.i(BENCH_TAG, msg)
        benchmarkLogCallback?.invoke(msg)
    }

    fun feedFrame(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        synchronized(lock) {
            _updateStats(data.size)
            if (videoWidth == 0 || videoHeight == 0) return

            if (codec == null || isH265 != currentH265) {
                // a stale reference frame decodes to corruption, so wait for a keyframe to (re)start
                if (!_isKeyframe(data, isH265)) {
                    if (codec != null) stopCodec()
                    return
                }
                stopCodec()
            }

            try {
                if (codec == null) startCodec(isH265)
                pending.addLast(data to ntpTimeNs / 1000)
                if (pending.size > MAX_PENDING) {
                    pending.removeFirst()
                    droppedFrames++
                }
                _submitPending()
            } catch (e: Exception) {
                Log.w(TAG, "Codec error, resetting", e)
                stopCodec()
            }
        }
    }

    private fun _submitPending() {
        val c = codec ?: return
        while (freeInputs.isNotEmpty() && pending.isNotEmpty()) {
            val idx = freeInputs.removeFirst()
            val (data, ptsUs) = pending.removeFirst()
            val buf = c.getInputBuffer(idx) ?: continue
            buf.clear()
            if (data.size > buf.capacity()) { droppedFrames++; freeInputs.addFirst(idx); continue }
            buf.put(data)
            c.queueInputBuffer(idx, 0, data.size, ptsUs, 0)
            firstFrameQueued = true
        }
    }

    private fun _callback(owner: MediaCodec) = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(c: MediaCodec, index: Int) = synchronized(lock) {
            if (c !== codec) return@synchronized
            freeInputs.addLast(index)
            try { _submitPending() } catch (e: Exception) { Log.w(TAG, "submit failed", e); stopCodec() }
        }
        override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            synchronized(lock) {
                if (c !== codec) return
                _recordOutputFrameTime()
                try {
                    if (scheduledOutputBufferRelease) {
                        val ptsUs = info.presentationTimeUs
                        if (_ptsBaseUs == Long.MIN_VALUE) { _ptsBaseUs = ptsUs; _wallBaseNs = System.nanoTime() }
                        c.releaseOutputBuffer(index, _wallBaseNs + (ptsUs - _ptsBaseUs) * 1000L)
                    } else {
                        c.releaseOutputBuffer(index, true)
                    }
                } catch (e: Exception) { Log.w(TAG, "release failed", e) }
            }
        }
        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
            Log.w(TAG, "decoder error (transient=${e.isTransient})", e)
            synchronized(lock) { if (c === codec && !e.isTransient) stopCodec() }
        }
        override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {
            Log.i(TAG, "decoder output format: $format")
        }
    }

    private fun _codecHandler(): Handler {
        codecHandler?.let { return it }
        val t = HandlerThread("VideoDecoder", Process.THREAD_PRIORITY_URGENT_DISPLAY).also { it.start() }
        codecThread = t
        return Handler(t.looper).also { codecHandler = it }
    }

    private fun _isKeyframe(data: ByteArray, isH265: Boolean): Boolean {
        if (data.size < 5) return false
        var i = 0
        while (i <= data.size - 5) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                val key = if (isH265) {
                    val type = (data[i + 4].toInt() shr 1) and 0x3F
                    type == 19 || type == 20 || type == 21 || type == 32 || type == 33
                } else {
                    val type = data[i + 4].toInt() and 0x1F
                    type == 5 || type == 7
                }
                if (key) return true
            }
            i++
        }
        return false
    }

    private fun startCodec(h265: Boolean) {
        val display = displaySurface
        val s = if (display != null && display.isValid) display else {
            pipeline.start()
            pipeline.setVideoSize(videoWidth, videoHeight)
            pipeline.inputSurface ?: return
        }
        direct = s === display
        currentH265 = h265
        val mime = if (h265) DecoderSelector.HEVC else DecoderSelector.AVC
        val info = (if (h265) hevcDecoder else avcDecoder) ?: error("no decoder selected for $mime")

        firstFrameQueued = false
        try {
            _startWithLadder(info, mime, s, h265)
        } catch (e: Exception) {
            // strict hw decoders reject configs beyond their real limits
            val sw = selector.software(mime, videoWidth, videoHeight) ?: throw e
            Log.w(TAG, "Hardware decoder failed, trying software fallback", e)
            _startWithLadder(sw, mime, s, h265)
        }
        Log.i(TAG, "Video codec started: $mime ${videoWidth}x${videoHeight} ($codecName) ${if (direct) "direct" else "via GL"}")
    }

    private fun _startWithLadder(info: MediaCodecInfo, mime: String, s: Surface, h265: Boolean) {
        var tryNum = 0
        while (true) {
            val format = _format(mime, info)
            val more = selector.lowLatencyOptions(format, info, mime, tryNum)
            try {
                _startDecoder(MediaCodec.createByCodecName(info.name), format, s, h265)
                return
            } catch (e: Exception) {
                if (!more) throw e
                Log.w(TAG, "configure try $tryNum failed: $format", e)
                tryNum++
            }
        }
    }

    private fun _format(mime: String, info: MediaCodecInfo) = MediaFormat.createVideoFormat(mime, videoWidth, videoHeight).apply {
        setInteger(MediaFormat.KEY_FRAME_RATE, maxFps)
        if (selector.adaptive(info, mime)) {
            setInteger(MediaFormat.KEY_MAX_WIDTH, videoWidth)
            setInteger(MediaFormat.KEY_MAX_HEIGHT, videoHeight)
        }
        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(videoWidth * videoHeight * 3 / 4, 1024 * 1024))
        if (enforceSdr) {
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, if (keyAllowFrameDrop) 1 else 0)
        }
    }

    private fun _startDecoder(c: MediaCodec, format: MediaFormat, surface: Surface, h265: Boolean) {
        try {
            c.setCallback(_callback(c), _codecHandler())
            c.configure(format, surface, null, 0)
            c.start()
        } catch (e: Exception) {
            try { c.release() } catch (_: Exception) {}
            throw e
        }
        codec = c
        codecName = (if (h265) "H.265" else "H.264") + " (${c.name})"
    }

    private fun stopCodec() {
        _frameIntervalIdx = 0
        _frameIntervalCount = 0
        _lastOutputFrameNs = 0L
        _ptsBaseUs = Long.MIN_VALUE
        _wallBaseNs = 0L
        freeInputs.clear()
        pending.clear()
        codec?.let {
            try {
                it.stop()
                it.release()
            } catch (_: Exception) {}
        }
        codec = null
    }

    fun release() = synchronized(lock) {
        stopCodec()
        pipeline.release()
        direct = false
        codecThread?.quitSafely()
        codecThread = null
        codecHandler = null
        _resetStats()
    }

    private var _outFrames = 0L
    private var _outFramesAtLog = 0L

    private fun _recordOutputFrameTime() {
        _outFrames++
        val now = System.nanoTime()
        if (_lastOutputFrameNs > 0) {
            _frameIntervalsNs[_frameIntervalIdx % _frameIntervalsNs.size] = now - _lastOutputFrameNs
            _frameIntervalIdx++
            _frameIntervalCount++
        }
        _lastOutputFrameNs = now
    }

    private fun _computeFramePacingJitterUs(): Long {
        val count = _frameIntervalCount.coerceAtMost(_frameIntervalsNs.size)
        if (count < 2) return 0

        var sum = 0.0
        var sumSq = 0.0
        for (i in 0 until count) {
            val interval = _frameIntervalsNs[i].toDouble()
            sum += interval
            sumSq += interval * interval
        }
        val mean = sum / count
        val variance = (sumSq / count) - (mean * mean)
        return (kotlin.math.sqrt(variance.coerceAtLeast(0.0)) / 1000.0).toLong()
    }

    companion object {
        private const val TAG = "VideoRenderer"
        private const val BENCH_TAG = "BENCHMARK"
        /** ~1 s at 60 fps; beyond this the decoder cannot keep up anyway. */
        private const val MAX_PENDING = 60
    }
}
