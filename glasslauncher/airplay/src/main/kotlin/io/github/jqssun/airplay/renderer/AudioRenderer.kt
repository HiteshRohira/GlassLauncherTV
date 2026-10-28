package io.github.jqssun.airplay.renderer

import io.github.jqssun.airplay.bridge.NativeBridge

// wrapper around native audio engine
class AudioRenderer {

    @Volatile var config = AudioConfig(); private set

    @Volatile var codecLabel = ""; private set
    @Volatile var clientGain = 1f; private set

    private var serverHandle = 0L

    @Synchronized
    fun attachEngine(server: Long) {
        serverHandle = server
        pushConfig()
        NativeBridge.nativeServerAudioSetClientGain(serverHandle, clientGain)
    }

    @Synchronized
    fun detachEngine() {
        serverHandle = 0L
        codecLabel = ""
    }

    // open playout (first onAudioFormat, or resume after pause); idempotent natively
    @Synchronized
    fun start() {
        if (serverHandle == 0L) return
        NativeBridge.nativeServerAudioStart(serverHandle)
    }

    // releases oboe stream + audio device while idle; engine stays alive, freed on server destroy
    @Synchronized
    fun stop() {
        if (serverHandle == 0L) return
        NativeBridge.nativeServerAudioStop(serverHandle)
        codecLabel = ""
    }

    @Synchronized
    fun setClientGain(gain: Float) {
        clientGain = gain
        if (serverHandle != 0L) NativeBridge.nativeServerAudioSetClientGain(serverHandle, gain)
    }

    @Synchronized
    fun updateConfig(newConfig: AudioConfig) {
        config = newConfig
        pushConfig()
    }

    private fun pushConfig() {
        if (serverHandle == 0L) return
        NativeBridge.nativeServerAudioConfigure(
            serverHandle, config.cushionMs, config.percentilePct, config.oboeBufferFrames,
            config.forceSwAlac, config.forceSwAac, config.realtimePriority, config.lowLatency,
            config.benchmarkLog)
    }

    @Synchronized
    fun setFormat(ct: Int, spf: Int) {
        codecLabel = when (ct) {
            CT_ALAC -> "ALAC"; CT_AAC_LC -> "AAC-LC"; CT_AAC_ELD -> "AAC-ELD"; else -> "?"
        }
        if (serverHandle != 0L) NativeBridge.nativeServerAudioFormat(serverHandle, ct, spf)
    }

    /** Read only on an explicit diagnostic dump; no polling or native layout changes. */
    @Synchronized
    fun performanceSnapshot(): String {
        if (!io.github.jqssun.airplay.diagnostics.PerfTrace.ENABLED) return "{\"enabled\":false}"
        if (serverHandle == 0L) return "{\"enabled\":true,\"engine\":false}"
        val b = java.nio.ByteBuffer.allocateDirect(DEBUG_BUF_BYTES).order(java.nio.ByteOrder.nativeOrder())
        if (!NativeBridge.nativeServerAudioDebug(serverHandle, b)) return "{\"enabled\":true,\"available\":false}"
        return org.json.JSONObject().put("enabled", true).put("active", codecLabel.isNotEmpty())
            .put("adaptive_percentile", config.percentilePct).put("configured_cushion_ms",config.cushionMs)
            .put("backlog_ms", b.short.toInt() and 0xffff).put("cushion_ms", b.short.toInt() and 0xffff)
            .put("trims", b.int.toLong() and 0xffffffffL).put("drops", b.int.toLong() and 0xffffffffL)
            .put("silences", b.int.toLong() and 0xffffffffL).put("underruns", b.int.toLong() and 0xffffffffL)
            .put("decode_mean_us", b.int).put("decode_max_us", b.int).put("decode_inflight", b.short.toInt() and 0xffff)
            .put("output_xruns", b.int).put("decode_errors", b.int).toString()
    }

    companion object {
        // must be >= native sizeof(AudioDebugData) (38 B)
        private const val DEBUG_BUF_BYTES = 64
        const val CT_ALAC = 2
        const val CT_AAC_LC = 4
        const val CT_AAC_ELD = 8
    }
}
