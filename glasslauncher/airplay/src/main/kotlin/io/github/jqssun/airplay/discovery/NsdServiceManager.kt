package io.github.jqssun.airplay.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log

data class MdnsService(val name: String, val type: String, val port: Int, val txt: Map<String, String>)

class NsdServiceManager(private val ctx: Context, private val onChange: () -> Unit = {}) {

    private val nsdManager = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var multicastLock: WifiManager.MulticastLock? = null
    private val registrations = mutableListOf<NsdManager.RegistrationListener>()
    private val registered = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val failed = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    /** Name actually registered per type; mDNS renames on a conflict. */
    private val names = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var expected = 0
    @Volatile private var released = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    fun registeredName(type: String): String? = names[type]

    fun allRegistered() = expected > 0 && registered.size >= expected
    fun anyFailed() = failed.isNotEmpty()

    /** Some firmware drops the multicast lock across Wi-Fi reconnects. */
    fun ensureMulticastLock() {
        val lock = multicastLock ?: return
        if (!lock.isHeld) lock.acquire()
    }

    fun acquireMulticastLock() {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("airplay_mdns").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    fun register(services: List<MdnsService>) {
        expected = services.size
        // Unregistering is asynchronous in mdnsd; registering the same name at
        // once can collide with our own old record and get renamed "(2)".
        val wait = lastReleaseAt + REREGISTER_GAP_MS - android.os.SystemClock.elapsedRealtime()
        if (wait <= 0) services.forEach(::_register)
        else handler.postDelayed({ if (!released) services.forEach(::_register) }, wait)
    }

    private fun _register(s: MdnsService) {
        val info = NsdServiceInfo().apply {
            serviceName = s.name
            serviceType = s.type
            port = s.port
            s.txt.forEach { (k, v) -> setAttribute(k, v) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "${s.type} registered: ${info.serviceName}")
                if (info.serviceName != s.name) Log.w(TAG, "${s.type} name taken on the network; registered as ${info.serviceName}")
                names[s.type] = info.serviceName
                failed -= s.type; registered += s.type; onChange()
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "${s.type} registration failed: $code")
                registered -= s.type; failed += s.type; onChange()
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "${s.type} unregistered")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "${s.type} unregister failed: $code")
            }
        }
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        registrations += listener
    }

    fun release() {
        released = true
        handler.removeCallbacksAndMessages(null)
        if (registrations.isNotEmpty()) lastReleaseAt = android.os.SystemClock.elapsedRealtime()
        names.clear()
        registrations.forEach {
            try { nsdManager.unregisterService(it) } catch (_: Exception) {}
        }
        registrations.clear()
        registered.clear(); failed.clear(); expected = 0
        multicastLock?.release()
        multicastLock = null
    }

    companion object {
        private const val TAG = "NsdServiceManager"
        private const val REREGISTER_GAP_MS = 400L
        @Volatile private var lastReleaseAt = -REREGISTER_GAP_MS
    }
}
