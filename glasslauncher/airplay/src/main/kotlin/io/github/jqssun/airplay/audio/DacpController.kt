package io.github.jqssun.airplay.audio

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Ordered sender controls. Resolution and queued work belong to one sender generation. */
class DacpController(ctx: Context) {
    private val nsdManager = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val exec = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(16))
    private val deadlines = Executors.newSingleThreadScheduledExecutor()
    private val pending = ConcurrentHashMap.newKeySet<SettableFuture<Unit>>()
    private val connections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    private data class Endpoint(val host: String, val port: Int, val token: String, val generation: Long)
    private var generation = 0L
    private var endpoint: Endpoint? = null
    private var closed = false

    @Volatile var dacpId = ""; private set
    @Volatile var activeRemote = ""; private set
    val ready: Boolean get() = synchronized(this) { !closed && endpoint != null }

    fun update(dacpId: String, activeRemote: String) {
        val gen = synchronized(this) {
            if (closed) return
            if (this.dacpId == dacpId && this.activeRemote == activeRemote && endpoint != null) return
            this.dacpId = dacpId
            this.activeRemote = activeRemote
            endpoint = null
            ++generation
        }
        if (dacpId.isEmpty() || activeRemote.isEmpty()) return
        val info = NsdServiceInfo().apply {
            serviceType = "_dacp._tcp"
            serviceName = "iTunes_Ctrl_$dacpId"
        }
        try {
            nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo, code: Int) {
                    Log.w(TAG, "DACP resolve failed: $code")
                }
                override fun onServiceResolved(si: NsdServiceInfo) {
                    val host = si.host?.hostAddress ?: return
                    if (si.port !in 1..65535) return
                    synchronized(this@DacpController) {
                        if (!closed && generation == gen)
                            endpoint = Endpoint(host, si.port, activeRemote, gen)
                    }
                }
            })
        } catch (e: Exception) { Log.w(TAG, "DACP resolve failed", e) }
    }

    fun play() = send("/ctrl-int/1/play")
    fun pause() = send("/ctrl-int/1/pause")
    fun nextItem() = send("/ctrl-int/1/nextitem")
    fun prevItem() = send("/ctrl-int/1/previtem")
    fun volumeUp() = send("/ctrl-int/1/volumeup")
    fun volumeDown() = send("/ctrl-int/1/volumedown")
    fun beginFastForward() = send("/ctrl-int/1/beginff")
    fun beginRewind() = send("/ctrl-int/1/beginrew")
    fun playResume() = send("/ctrl-int/1/playresume")

    @Synchronized fun reset() {
        generation++
        endpoint = null
        dacpId = ""
        activeRemote = ""
    }

    fun release() {
        synchronized(this) { closed = true; reset() }
        exec.shutdownNow()
        deadlines.shutdownNow()
        Thread({
            connections.forEach { it.disconnect() }
            connections.clear()
        }, "mirage-dacp-close").apply { isDaemon = true; start() }
        pending.forEach { it.setException(IOException("Sender controls closed")) }
        pending.clear()
    }

    private fun send(path: String): ListenableFuture<Unit> {
        val result = SettableFuture.create<Unit>()
        val target = synchronized(this) { if (closed) null else endpoint }
        if (target == null) {
            result.setException(IOException("Sender controls are not connected"))
            return result
        }
        pending.add(result)
        try {
            exec.execute {
                var connection: HttpURLConnection? = null
                var deadline: java.util.concurrent.ScheduledFuture<*>? = null
                try {
                    synchronized(this) {
                        if (closed || generation != target.generation || result.isCancelled)
                            throw IOException("Sender changed")
                    }
                    val conn = URL("http", target.host, target.port, path).openConnection() as HttpURLConnection
                    connection = conn
                    connections.add(conn)
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("Active-Remote", target.token)
                    conn.connectTimeout = 2000
                    conn.readTimeout = 2000
                    deadline = deadlines.schedule({
                        result.setException(IOException("Sender control timed out"))
                        conn.disconnect()
                    }, 5, TimeUnit.SECONDS)
                    val code = conn.responseCode
                    // The body is unused. Do not allocate or drain an untrusted stream.
                    if (code in 200..299) result.set(Unit)
                    else result.setException(IOException("HTTP $code"))
                } catch (e: Exception) {
                    result.setException(e)
                } finally {
                    deadline?.cancel(false)
                    connection?.let { connections.remove(it); it.disconnect() }
                    pending.remove(result)
                }
            }
        } catch (e: Exception) {
            pending.remove(result)
            result.setException(e)
        }
        return result
    }

    companion object { private const val TAG = "DacpController" }
}
