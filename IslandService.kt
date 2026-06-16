package com.maaz.dynamicisland

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Owns the floating window. Listens on IslandBus for content and morphs the
 * pill between three states:
 *   COLLAPSED  - tiny black pill hugging the camera cutout
 *   NOTIF      - expands to show an incoming notification, then auto-collapses
 *   MEDIA      - persistent now-playing card with transport controls
 */
class IslandService : Service(), IslandListener {

    private lateinit var wm: WindowManager
    private lateinit var root: View
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var bg: GradientDrawable
    private val main = Handler(Looper.getMainLooper())
    private var density = 1f

    private lateinit var notifContent: LinearLayout
    private lateinit var notifIcon: ImageView
    private lateinit var notifTitle: TextView
    private lateinit var notifText: TextView
    private lateinit var mediaContent: LinearLayout
    private lateinit var mediaArt: ImageView
    private lateinit var mediaTitle: TextView
    private lateinit var mediaArtist: TextView
    private lateinit var btnPlay: ImageView
    private lateinit var btnPrev: ImageView
    private lateinit var btnNext: ImageView

    private var mode = Mode.COLLAPSED
    private var hasMedia = false
    private var sizeAnim: ValueAnimator? = null

    private var currentNotif: IslandContent.Notification? = null
    private var currentMediaPkg: String? = null

    private val collapseRunnable = Runnable { collapseToMediaOrIdle() }

    enum class Mode { COLLAPSED, NOTIF, MEDIA }

    companion object {
        const val ACTION_START = "com.maaz.dynamicisland.START"
        const val ACTION_STOP = "com.maaz.dynamicisland.STOP"
        const val ACTION_CALIBRATE = "com.maaz.dynamicisland.CALIBRATE"
        private const val CHANNEL = "island_fgs"
        private const val NOTIF_ID = 1001

        @Volatile
        var running = false
            private set
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        density = resources.displayMetrics.density
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        startInForeground()
        inflateOverlay()
        IslandBus.register(this)
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CALIBRATE -> applyCalibration()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        IslandBus.unregister(this)
        main.removeCallbacks(collapseRunnable)
        sizeAnim?.cancel()
        try {
            if (::root.isInitialized) wm.removeView(root)
        } catch (_: Exception) {
        }
        running = false
        super.onDestroy()
    }

    // ---------- Foreground plumbing ----------

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(
            CHANNEL, "Dynamic Island", NotificationManager.IMPORTANCE_MIN
        ).apply { setShowBadge(false) }
        nm.createNotificationChannel(ch)
    }

    private fun startInForeground() {
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Dynamic Island active")
            .setContentText("Open the app to calibrate or stop it.")
            .setSmallIcon(R.drawable.ic_bell)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // ---------- Overlay setup ----------

    private fun dp(v: Float) = (v * density).toInt()

    private fun inflateOverlay() {
        root = LayoutInflater.from(this).inflate(R.layout.island_view, null)

        notifContent = root.findViewById(R.id.notifContent)
        notifIcon = root.findViewById(R.id.notifIcon)
        notifTitle = root.findViewById(R.id.notifTitle)
        notifText = root.findViewById(R.id.notifText)
        mediaContent = root.findViewById(R.id.mediaContent)
        mediaArt = root.findViewById(R.id.mediaArt)
        mediaTitle = root.findViewById(R.id.mediaTitle)
        mediaArtist = root.findViewById(R.id.mediaArtist)
        btnPlay = root.findViewById(R.id.btnPlay)
        btnPrev = root.findViewById(R.id.btnPrev)
        btnNext = root.findViewById(R.id.btnNext)

        bg = GradientDrawable().apply {
            setColor(Color.BLACK)
            cornerRadius = dp(Prefs.corner(this@IslandService)).toFloat()
        }
        root.background = bg
        root.clipToOutline = true

        notifIcon.imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)

        btnPlay.setOnClickListener { NotificationService.instance?.mediaPlayPause() }
        btnPrev.setOnClickListener { NotificationService.instance?.mediaPrev() }
        btnNext.setOnClickListener { NotificationService.instance?.mediaNext() }

        root.setOnClickListener { onIslandTap() }
        root.setOnLongClickListener { onIslandLongPress(); true }

        lp = WindowManager.LayoutParams(
            dp(Prefs.collapsedW(this)),
            dp(Prefs.collapsedH(this)),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(Prefs.yOffset(this@IslandService))
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        wm.addView(root, lp)
        setMode(Mode.COLLAPSED, animate = false)
    }

    private fun applyCalibration() {
        if (!::lp.isInitialized) return
        lp.y = dp(Prefs.yOffset(this))
        bg.cornerRadius = dp(Prefs.corner(this)).toFloat()
        if (mode == Mode.COLLAPSED) {
            lp.width = dp(Prefs.collapsedW(this))
            lp.height = dp(Prefs.collapsedH(this))
        } else {
            lp.width = dp(Prefs.expandedW(this))
        }
        safeUpdate()
    }

    private fun safeUpdate() {
        try {
            wm.updateViewLayout(root, lp)
        } catch (_: Exception) {
        }
    }

    // ---------- IslandListener ----------

    override fun onContent(content: IslandContent) {
        when (content) {
            is IslandContent.Notification -> showNotification(content)
            is IslandContent.Media -> showMedia(content)
            IslandContent.Idle -> setMode(Mode.COLLAPSED, animate = true)
        }
    }

    override fun onMediaGone() {
        hasMedia = false
        currentMediaPkg = null
        if (mode == Mode.MEDIA) setMode(Mode.COLLAPSED, animate = true)
    }

    private fun showNotification(n: IslandContent.Notification) {
        currentNotif = n
        notifTitle.text = if (!n.title.isNullOrBlank()) n.title else n.appName
        notifText.text = n.text ?: ""
        n.smallIcon?.let { notifIcon.setImageIcon(it) }
        setMode(Mode.NOTIF, animate = true)
        main.removeCallbacks(collapseRunnable)
        main.postDelayed(collapseRunnable, 3500)
    }

    private fun showMedia(m: IslandContent.Media) {
        hasMedia = true
        currentMediaPkg = m.packageName
        mediaTitle.text = if (!m.title.isNullOrBlank()) m.title else "Now playing"
        mediaArtist.text = m.artist ?: ""
        if (m.art != null) mediaArt.setImageBitmap(m.art)
        else mediaArt.setImageResource(R.drawable.ic_note)
        btnPlay.setImageResource(if (m.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        // Don't steal the screen while a notification is being shown.
        if (mode != Mode.NOTIF) setMode(Mode.MEDIA, animate = true)
    }

    private fun collapseToMediaOrIdle() {
        if (hasMedia) setMode(Mode.MEDIA, animate = true)
        else setMode(Mode.COLLAPSED, animate = true)
    }

    // ---------- Mode + animation ----------

    private fun setMode(target: Mode, animate: Boolean) {
        mode = target
        when (target) {
            Mode.COLLAPSED -> {
                fade(notifContent, false)
                fade(mediaContent, false)
                resize(dp(Prefs.collapsedW(this)), dp(Prefs.collapsedH(this)), animate)
            }
            Mode.NOTIF -> {
                fade(mediaContent, false)
                fade(notifContent, true)
                resize(dp(Prefs.expandedW(this)), dp(66f), animate)
            }
            Mode.MEDIA -> {
                fade(notifContent, false)
                fade(mediaContent, true)
                resize(dp(Prefs.expandedW(this)), dp(74f), animate)
            }
        }
    }

    private fun resize(w: Int, h: Int, animate: Boolean) {
        sizeAnim?.cancel()
        if (!animate) {
            lp.width = w
            lp.height = h
            safeUpdate()
            return
        }
        val startW = lp.width
        val startH = lp.height
        sizeAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            interpolator = OvershootInterpolator(1.05f)
            addUpdateListener {
                val f = it.animatedValue as Float
                lp.width = (startW + (w - startW) * f).toInt()
                lp.height = (startH + (h - startH) * f).toInt()
                safeUpdate()
            }
            start()
        }
    }

    private fun fade(v: View, show: Boolean) {
        if (show) {
            v.visibility = View.VISIBLE
            v.animate().alpha(1f).setDuration(220).withStartAction { }.start()
        } else {
            v.animate().alpha(0f).setDuration(120)
                .withEndAction { v.visibility = View.GONE }.start()
        }
    }

    // ---------- Interaction ----------

    private fun onIslandTap() {
        when (mode) {
            Mode.MEDIA -> launchPackage(currentMediaPkg)
            Mode.NOTIF -> {
                val intent = currentNotif?.contentIntent
                if (intent != null) {
                    try {
                        intent.send()
                    } catch (_: PendingIntent.CanceledException) {
                        launchPackage(currentNotif?.packageName)
                    }
                } else {
                    launchPackage(currentNotif?.packageName)
                }
            }
            Mode.COLLAPSED -> if (hasMedia) setMode(Mode.MEDIA, animate = true)
        }
    }

    private fun onIslandLongPress() {
        when (mode) {
            Mode.COLLAPSED -> if (hasMedia) setMode(Mode.MEDIA, animate = true)
            else -> setMode(Mode.COLLAPSED, animate = true)
        }
    }

    private fun launchPackage(pkg: String?) {
        pkg ?: return
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(launch)
        } catch (_: Exception) {
        }
    }
}
