package tv.glass.airplay

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import io.github.jqssun.airplay.service.AirPlayService

/**
 * Full-screen AirPlay surface for Glass. Shows mirroring, AirPlay video and
 * music in the launcher's glass style. It lives in the receiver's process so
 * it can hand surfaces to the running engine directly, and it closes itself
 * when the sender disconnects.
 */
class AirPlayPlayerActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout
    private lateinit var mirror: AspectSurface
    private lateinit var video: AspectSurface
    private lateinit var artBackdrop: ImageView
    private lateinit var waiting: LinearLayout
    private lateinit var waitingTitle: TextView
    private lateinit var waitingBody: TextView
    private lateinit var music: LinearLayout
    private lateinit var musicArt: ImageView
    private lateinit var musicTitle: TextView
    private lateinit var musicArtist: TextView
    private lateinit var musicProgress: ProgressBar
    private lateinit var musicTime: TextView
    private lateinit var controls: LinearLayout
    private lateinit var controlsTitle: TextView
    private lateinit var controlsState: TextView
    private lateinit var controlsProgress: ProgressBar
    private lateinit var controlsTime: TextView
    private lateinit var hint: TextView

    private var lastArt: Bitmap? = null
    private var idleSince = 0L
    private var stopArmedAt = 0L
    private var started = false

    private val listener = Runnable { render() }
    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 500)
        }
    }
    private val hideControls = Runnable { controls.animate().alpha(0f).setDuration(250).start() }
    private val hideHint = Runnable { hint.animate().alpha(0f).setDuration(250).start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        GlassStyle.init(this)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        artBackdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.55f
            if (Build.VERSION.SDK_INT >= 31) setRenderEffect(RenderEffect.createBlurEffect(90f, 90f, Shader.TileMode.CLAMP))
            visibility = View.GONE
        }
        root.addView(artBackdrop, FrameLayout.LayoutParams(-1, -1))

        mirror = AspectSurface(this, object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { AirPlayService.instance?.setVideoSurface(h.surface) }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) { AirPlayService.instance?.setVideoSurface(h.surface) }
            override fun surfaceDestroyed(h: SurfaceHolder) { AirPlayService.instance?.clearVideoSurface(h.surface) }
        })
        video = AspectSurface(this, object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { AirPlayService.instance?.setVideoPlaybackSurface(h.surface) }
            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) { AirPlayService.instance?.setVideoPlaybackSurface(h.surface) }
            override fun surfaceDestroyed(h: SurfaceHolder) { AirPlayService.instance?.clearVideoPlaybackSurface(h.surface) }
        })
        root.addView(mirror, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        root.addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))

        waiting = card().apply { gravity = Gravity.CENTER_HORIZONTAL }
        waiting.addView(ImageView(this).apply {
            setImageDrawable(GlassStyle.airplayGlyph(this@AirPlayPlayerActivity))
        }, LinearLayout.LayoutParams(px(44), px(44)))
        waitingTitle = text("Connecting…", 26, GlassStyle.regular, Color.WHITE).apply { gravity = Gravity.CENTER }
        waitingBody = text("", 14, GlassStyle.regular, 0xFFD4CEDA.toInt()).apply { gravity = Gravity.CENTER }
        waiting.addView(waitingTitle, lp(-2, -2, top = 16))
        waiting.addView(waitingBody, lp(-2, -2, top = 8))
        root.addView(waiting, FrameLayout.LayoutParams(px(460), -2, Gravity.CENTER))

        music = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        musicArt = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = GlassStyle.plate(this@AirPlayPlayerActivity, 18f, false)
            elevation = px(18).toFloat()
        }
        music.addView(musicArt, LinearLayout.LayoutParams(px(300), px(300)))
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        words.addView(text("AirPlay music", 13, GlassStyle.medium, 0xB3FFFFFF.toInt()).apply { letterSpacing = .12f; isAllCaps = true })
        musicTitle = text("", 34, GlassStyle.regular, Color.WHITE).apply { maxLines = 2 }
        musicArtist = text("", 18, GlassStyle.light, 0xFFE6E1EA.toInt()).apply { maxLines = 1 }
        words.addView(musicTitle, lp(-1, -2, top = 10))
        words.addView(musicArtist, lp(-1, -2, top = 6))
        musicProgress = progress()
        words.addView(musicProgress, lp(-1, px(4), top = 28))
        musicTime = text("", 13, GlassStyle.regular, 0xB3FFFFFF.toInt())
        words.addView(musicTime, lp(-1, -2, top = 10))
        words.addView(text("OK  Play / Pause     ◀ ▶  Previous / Next", 13, GlassStyle.regular, 0x99FFFFFF.toInt()), lp(-1, -2, top = 26))
        music.addView(words, LinearLayout.LayoutParams(px(520), -2).apply { marginStart = px(48) })
        root.addView(music, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        controls = card().apply { alpha = 0f }
        controlsState = text("", 12, GlassStyle.medium, 0xB3FFFFFF.toInt()).apply { letterSpacing = .12f; isAllCaps = true }
        controlsTitle = text("", 22, GlassStyle.regular, Color.WHITE).apply { maxLines = 1 }
        controlsProgress = progress()
        controlsTime = text("", 13, GlassStyle.regular, 0xCCFFFFFF.toInt())
        controls.addView(controlsState)
        controls.addView(controlsTitle, lp(-1, -2, top = 6))
        controls.addView(controlsProgress, lp(-1, px(4), top = 16))
        controls.addView(controlsTime, lp(-1, -2, top = 10))
        root.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = px(68); rightMargin = px(68); bottomMargin = px(48)
        })

        hint = text("", 15, GlassStyle.regular, Color.WHITE).apply {
            background = GlassStyle.plate(this@AirPlayPlayerActivity, 22f, true)
            setPadding(px(20), px(11), px(20), px(11))
            alpha = 0f
        }
        root.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = px(40) })
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        if (AirPlayService.instance == null) {
            ContextCompat.startForegroundService(this, Intent(this, AirPlayService::class.java)
                .setAction(AirPlayService.ACTION_START_SERVER))
        }
        started = true
        idleSince = 0L
        AirPlayService.addListener(listener)
        handler.post(ticker)
    }

    override fun onStop() {
        started = false
        AirPlayService.removeListener(listener)
        handler.removeCallbacksAndMessages(null)
        super.onStop()
    }

    private fun render() {
        val service = AirPlayService.instance
        val kind = service?.sessionKind() ?: "idle"
        val pin = service?.currentPin()
        val pending = service?.videoSessionPending() == true

        val mirroring = kind == "mirroring"
        val playing = kind == "video" || pending
        val audio = kind == "audio"
        show(mirror, mirroring)
        show(video, playing)
        if (mirroring) mirror.setAspect(service!!.videoAspect.value)
        if (playing) video.setAspect(service!!.videoPlaybackAspect.value)

        show(music, audio)
        show(artBackdrop, audio && lastArt != null)
        if (audio) renderMusic(service!!)

        val showWaiting = pin != null || (!mirroring && !playing && !audio)
        show(waiting, showWaiting)
        if (pin != null) {
            waitingTitle.text = pin.chunked(2).joinToString("  ")
            waitingTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(44).toFloat())
            waitingBody.text = "Enter this code on your iPhone, iPad or Mac"
        } else {
            waitingTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(26).toFloat())
            waitingTitle.text = if (kind == "connecting") "Connecting…" else "Ready for AirPlay"
            waitingBody.text = "Choose “${service?.advertisedName()?.ifBlank { null } ?: "this TV"}” in Screen Mirroring or the AirPlay menu"
        }

        if (playing && service != null) {
            val info = service.videoPlaybackInfo.value
            controlsState.text = if (info.buffering || pending) "Loading" else if (info.playing) "Playing" else "Paused"
            controlsTitle.text = service.videoTitle.value.ifBlank { "AirPlay video" }
            setProgress(controlsProgress, controlsTime, info.positionMs, info.durationMs)
            if (!info.playing && controls.alpha < 1f) revealControls(false)
        }

        // Close shortly after the sender leaves; a brief gap is normal while
        // a sender switches between mirroring and video.
        // A connection with no stream (after mirroring stops, the sender may
        // linger) gets longer, since a new stream may still be negotiating.
        if ((kind == "idle" || kind == "connecting") && pin == null && !pending) {
            val limit = if (kind == "idle") IDLE_CLOSE_MS else CONNECTING_CLOSE_MS
            if (idleSince == 0L) idleSince = SystemClock.elapsedRealtime()
            else if (SystemClock.elapsedRealtime() - idleSince > limit) finish()
        } else idleSince = 0L
    }

    private fun renderMusic(service: AirPlayService) {
        val track = service.trackInfo.value
        musicTitle.text = track.title.ifBlank { "AirPlay audio" }
        musicArtist.text = listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" · ")
        if (track.coverArt !== lastArt) {
            lastArt = track.coverArt
            musicArt.setImageBitmap(track.coverArt)
            artBackdrop.setImageBitmap(track.coverArt)
            if (track.coverArt == null) musicArt.setImageDrawable(GlassStyle.airplayGlyph(this))
        }
        setProgress(musicProgress, musicTime, service.currentPositionMs(), service.durationMs.value)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val service = AirPlayService.instance
        val kind = service?.sessionKind() ?: "idle"
        val video = kind == "video"
        if (event.action != KeyEvent.ACTION_DOWN) return isHandled(event.keyCode) || super.dispatchKeyEvent(event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (service == null) return true
                when {
                    video -> { service.setVideoPlaying(!service.videoPlaybackInfo.value.playing); revealControls(true) }
                    kind == "audio" -> service.togglePlayPause()
                    kind == "mirroring" -> flash("Use your iPhone to control mirroring")
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (video) { service?.seekVideoBy(AirPlayService.VIDEO_SEEK_STEP_MS); revealControls(true) }
                else if (kind == "audio") service?.dacpController?.nextItem()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (video) { service?.seekVideoBy(-AirPlayService.VIDEO_SEEK_STEP_MS); revealControls(true) }
                else if (kind == "audio") service?.dacpController?.prevItem()
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_INFO -> {
                if (video) revealControls(true)
                return true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                when {
                    video -> service?.stopVideoPlayback()
                    kind == "mirroring" -> {
                        val now = SystemClock.elapsedRealtime()
                        if (now - stopArmedAt < STOP_CONFIRM_MS) { service?.disconnectSenders(); finish() }
                        else { stopArmedAt = now; flash("Press Back again to stop mirroring") }
                    }
                    // Music keeps playing in the background, like any audio app.
                    else -> finish()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isHandled(code: Int) = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_CENTER ||
        code == KeyEvent.KEYCODE_ENTER || code in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT

    private fun revealControls(autoHide: Boolean) {
        handler.removeCallbacks(hideControls)
        controls.animate().alpha(1f).setDuration(150).start()
        if (autoHide) handler.postDelayed(hideControls, CONTROLS_MS)
    }

    private fun flash(message: String) {
        hint.text = message
        handler.removeCallbacks(hideHint)
        hint.animate().alpha(1f).setDuration(150).start()
        handler.postDelayed(hideHint, 2_500)
    }

    private fun setProgress(bar: ProgressBar, label: TextView, positionMs: Long, durationMs: Long) {
        bar.visibility = if (durationMs > 0) View.VISIBLE else View.INVISIBLE
        bar.progress = if (durationMs > 0) (1000 * positionMs.coerceIn(0, durationMs) / durationMs).toInt() else 0
        label.text = if (durationMs > 0) "${clock(positionMs)}  /  ${clock(durationMs)}" else if (positionMs > 0) clock(positionMs) else ""
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private fun show(v: View, visible: Boolean) {
        val target = if (visible) View.VISIBLE else View.GONE
        if (v.visibility != target) v.visibility = target
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GlassStyle.plate(this@AirPlayPlayerActivity, 28f, true)
        setPadding(px(32), px(28), px(32), px(28))
        elevation = px(14).toFloat()
    }

    private fun progress() = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progressDrawable = GlassStyle.progressDrawable(this@AirPlayPlayerActivity)
    }

    private fun text(value: String, size: Int, face: Typeface, color: Int) = TextView(this).apply {
        text = value
        typeface = face
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size).toFloat())
        includeFontPadding = false
    }

    private fun lp(w: Int, h: Int, top: Int = 0) = LinearLayout.LayoutParams(w, h).apply { topMargin = px(top) }

    private fun px(v: Int) = GlassStyle.px(this, v.toFloat())

    /** SurfaceView that letterboxes itself to the sender's aspect ratio. */
    private class AspectSurface(ctx: Activity, cb: SurfaceHolder.Callback) : SurfaceView(ctx) {
        private var aspect = 16f / 9f
        init { holder.addCallback(cb) }
        fun setAspect(a: Float) {
            if (a <= 0f || kotlin.math.abs(a - aspect) < 0.001f) return
            aspect = a
            requestLayout()
        }
        override fun onMeasure(w: Int, h: Int) {
            val pw = MeasureSpec.getSize(w)
            val ph = MeasureSpec.getSize(h)
            if (pw == 0 || ph == 0) return super.onMeasure(w, h)
            if (pw / ph.toFloat() > aspect) setMeasuredDimension((ph * aspect).toInt(), ph)
            else setMeasuredDimension(pw, (pw / aspect).toInt())
        }
    }

    companion object {
        private const val IDLE_CLOSE_MS = 2_500L
        private const val CONNECTING_CLOSE_MS = 10_000L
        private const val STOP_CONFIRM_MS = 3_000L
        private const val CONTROLS_MS = 4_000L
    }
}

/** The launcher's glass material, reproduced for the receiver's surfaces. */
internal object GlassStyle {
    lateinit var light: Typeface; lateinit var regular: Typeface; lateinit var medium: Typeface

    fun init(activity: Activity) {
        if (this::regular.isInitialized) return
        fun face(w: Int) = try {
            Typeface.Builder(activity.assets, "fonts/outfit.ttf").setFontVariationSettings("'wght' $w").setWeight(w).build()
        } catch (e: RuntimeException) { Typeface.create(Typeface.DEFAULT, w, false) }
        light = face(300); regular = face(400); medium = face(500)
    }

    fun px(a: Activity, v: Float) = Math.round(v * a.resources.displayMetrics.widthPixels / 1280f)

    fun plate(a: Activity, radius: Float, panel: Boolean) = GradientDrawable(GradientDrawable.Orientation.TL_BR,
        if (panel) intArrayOf(0xE33B3C43.toInt(), 0xEF181C27.toInt(), 0xED28222E.toInt())
        else intArrayOf(0x26FFFFFF, 0x0CFFFFFF, 0x18FFFFFF)).apply {
        cornerRadius = px(a, radius).toFloat()
        setStroke(maxOf(1, px(a, .8f)), if (panel) 0x45FFFFFF else 0x29FFFFFF)
    }

    fun progressDrawable(a: Activity): android.graphics.drawable.Drawable {
        val track = GradientDrawable().apply { setColor(0x33FFFFFF); cornerRadius = px(a, 2f).toFloat() }
        val fill = GradientDrawable().apply { setColor(0xFFF4F1F7.toInt()); cornerRadius = px(a, 2f).toFloat() }
        return android.graphics.drawable.LayerDrawable(arrayOf(track,
            android.graphics.drawable.ClipDrawable(fill, Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL))).apply {
            setId(0, android.R.id.background); setId(1, android.R.id.progress)
        }
    }

    /** Feather "airplay" outline, drawn in code so the module needs no resources. */
    fun airplayGlyph(a: Activity) = object : android.graphics.drawable.Drawable() {
        private val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE; color = 0xFFF1EDF7.toInt()
            strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND
        }
        override fun draw(c: android.graphics.Canvas) {
            val s = bounds.width() / 24f
            c.save(); c.translate(bounds.left.toFloat(), bounds.top.toFloat()); c.scale(s, s); p.strokeWidth = 1.6f
            val path = android.graphics.Path().apply {
                moveTo(5f, 17f); lineTo(4f, 17f); quadTo(2f, 17f, 2f, 15f); lineTo(2f, 5f); quadTo(2f, 3f, 4f, 3f)
                lineTo(20f, 3f); quadTo(22f, 3f, 22f, 5f); lineTo(22f, 15f); quadTo(22f, 17f, 20f, 17f); lineTo(19f, 17f)
                moveTo(12f, 15f); lineTo(17f, 21f); lineTo(7f, 21f); close()
            }
            c.drawPath(path, p); c.restore()
        }
        override fun setAlpha(alpha: Int) { p.alpha = alpha }
        override fun setColorFilter(cf: android.graphics.ColorFilter?) { p.colorFilter = cf }
        @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }
}
