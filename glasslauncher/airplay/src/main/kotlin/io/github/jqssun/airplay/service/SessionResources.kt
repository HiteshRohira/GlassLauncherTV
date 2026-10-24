package io.github.jqssun.airplay.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.PowerManager
import android.util.Log

/**
 * Glass: system resources held only while a sender is actually mirroring,
 * playing video or playing audio, and released as soon as it stops.
 *
 * - Partial wake lock: the receiver used to hold one for its whole life, so
 *   the SoC could never suspend in standby, even though an iPhone cannot wake
 *   the TV anyway.
 * - Wi-Fi low-latency lock (API 29+, high-performance before): turns off Wi-Fi
 *   power save, which adds beacon-interval jitter on 2.4 GHz.
 * - Audio focus: one owner for the whole receiver, so AirPlay pauses Plezy or
 *   music instead of mixing with it, and pauses itself when another app takes
 *   focus. ExoPlayer's own focus handling stays off to avoid two owners.
 */
class SessionResources(context: Context, private val handler: Handler, private val listener: Listener) {

    interface Listener {
        /** Another app took audio focus; [transient] means it will return it. */
        fun onFocusLost(transient: Boolean)
        /** Focus came back after a transient loss. */
        fun onFocusRegained()
    }

    private val app = context.applicationContext
    private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false
    private var lostTransiently = false
    var active = false; private set

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                if (lostTransiently) { lostTransiently = false; listener.onFocusRegained() }
            }
            AudioManager.AUDIOFOCUS_LOSS -> { hasFocus = false; lostTransiently = false; listener.onFocusLost(false) }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { lostTransiently = true; listener.onFocusLost(true) }
            // ducking is applied by the system on API 26+
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> Unit
        }
    }

    /** Call on the main thread whenever the session state may have changed. */
    fun update(sessionActive: Boolean) {
        if (sessionActive == active) return
        active = sessionActive
        if (sessionActive) acquire() else release()
    }

    private fun acquire() {
        try {
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "airplay:session").apply {
                setReferenceCounted(false); acquire(MAX_HOLD_MS)
            }
        } catch (e: RuntimeException) { Log.w(TAG, "wake lock refused", e) }
        try {
            val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wifi.createWifiLock(mode, "airplay:session").apply { setReferenceCounted(false); acquire() }
        } catch (e: RuntimeException) { Log.w(TAG, "wifi lock refused", e) }
        requestFocus()
        Log.i(TAG, "session resources held")
    }

    private fun requestFocus() {
        if (Build.VERSION.SDK_INT < 26) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(focusListener, handler)
            .build()
        focusRequest = request
        hasFocus = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (!hasFocus) Log.w(TAG, "audio focus denied")
    }

    fun release() {
        active = false
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { audio.abandonAudioFocusRequest(it) }
        focusRequest = null
        hasFocus = false
        lostTransiently = false
    }

    companion object {
        private const val TAG = "AirPlaySession"
        /** Safety net: a stuck session state cannot keep the CPU awake forever. */
        private const val MAX_HOLD_MS = 6 * 60 * 60 * 1000L
    }
}
