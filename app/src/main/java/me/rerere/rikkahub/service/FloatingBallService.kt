package me.rerere.rikkahub.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.rerere.rikkahub.FLOATING_BALL_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.hooks.readStringPreference
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import me.rerere.rikkahub.ui.pages.quickchat.QuickChatActivity
import me.rerere.rikkahub.ui.theme.ColorMode
import me.rerere.rikkahub.ui.theme.findPresetTheme
import me.rerere.rikkahub.ui.theme.findThemeById
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import kotlin.math.abs

private const val TAG = "FloatingBallService"

/**
 * Always-available floating ball.
 *
 * A tiny circular overlay (TYPE_APPLICATION_OVERLAY) that survives across apps so the user can
 * reach the agent without leaving what they are doing:
 *  - tap    → open [QuickChatActivity], a half-screen quick-chat panel
 *  - long   → open the same panel with voice input already recording
 *  - drag   → move it; it snaps to the nearest horizontal edge and remembers where it was
 *
 * Runs as a `specialUse` foreground service so the overlay is not evicted when the app goes to
 * the background. The overlay window itself is what exempts us from Android 14+ background
 * activity-start restrictions, so the panel can be launched from here at any time.
 *
 * Deliberately a no-op when SYSTEM_ALERT_WINDOW has not been granted: the caller (settings page /
 * boot receiver) is expected to have checked, but a stale start must never crash or leave a
 * headless foreground service behind.
 */
class FloatingBallService : Service(), KoinComponent {

    companion object {
        const val ACTION_START = "me.rerere.rikkahub.action.FLOATING_BALL_START"
        const val ACTION_STOP = "me.rerere.rikkahub.action.FLOATING_BALL_STOP"

        /** The quick-chat panel came up / went away: the ball hides while it is on screen. */
        const val ACTION_PANEL_OPEN = "me.rerere.rikkahub.action.FLOATING_BALL_PANEL_OPEN"
        const val ACTION_PANEL_CLOSE = "me.rerere.rikkahub.action.FLOATING_BALL_PANEL_CLOSE"
        const val NOTIFICATION_ID = 2003

        private const val PREF_X = "floating_ball_x"
        private const val PREF_Y = "floating_ball_y"
        private const val LONG_PRESS_MS = 420L
        private const val BALL_SIZE_DP = 56
        private const val BALL_ICON_DP = 28

        /** Used only if the app theme cannot be resolved; the default look follows the theme. */
        private const val FALLBACK_BALL_COLOR = 0xFF6750A4.toInt()

        /** Neutral base under a user-picked icon; the bitmap covers it via a circular crop. */
        private const val CUSTOM_ICON_BASE_COLOR = 0xFFFFFFFF.toInt()

        private const val IDLE_ANIM_MS = 220L

        // Same SharedPreferences file/key the UI uses (see ui/hooks/ColorMode.kt). The ball watches
        // it so a light/dark switch re-tints the ball without restarting the service.
        private const val PREFS_NAME = "rikkahub.preferences"
        private const val COLOR_MODE_KEY = "colorMode"
        private const val AMOLED_DARK_KEY = "amoledDark"

        /**
         * Where a user-picked ball image lives. The app's private files dir needs no storage
         * permission and is wiped on uninstall, so a stale settings path can never leak data.
         */
        fun iconFile(context: Context): File = File(context.filesDir, "floating_ball_icon")

        /** How much of the ball stays on screen once it has tucked itself into the edge. */
        private const val HIDDEN_VISIBLE_FRACTION = 0.5f

        /** Starts (or re-starts) the ball. Idempotent — a running service just re-shows its view. */
        fun start(context: Context) {
            val intent = Intent(context, FloatingBallService::class.java).apply {
                action = ACTION_START
            }
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.w(TAG, "start failed", it) }
        }

        /**
         * Stops the ball. Uses startService (not stopService) so the running instance handles
         * ACTION_STOP on its own looper and tears its overlay view down first.
         */
        fun stop(context: Context) {
            val intent = Intent(context, FloatingBallService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "stop failed", it) }
        }

        /**
         * Tells a running ball that the quick-chat panel opened / closed, so it can step out of
         * the way (hidden while the panel is up) and come back afterwards. Deliberately a plain
         * startService: the ball is already a foreground service whenever the panel can be open,
         * and a no-op command on a dead instance must not resurrect it.
         */
        fun notifyPanel(context: Context, open: Boolean) {
            val intent = Intent(context, FloatingBallService::class.java).apply {
                action = if (open) ACTION_PANEL_OPEN else ACTION_PANEL_CLOSE
            }
            runCatching { context.startService(intent) }
                .onFailure { Log.w(TAG, "notifyPanel failed", it) }
        }
    }

    private val settingsStore: SettingsStore by inject()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ballView: FrameLayout? = null
    private var ballIconView: ImageView? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var longPressFired = false

    /** Cache so repainting on unrelated settings changes does not re-decode the picked image. */
    private var loadedIconPath: String? = null
    private var loadedIconStamp: Long = 0L
    private var loadedIconBitmap: Bitmap? = null

    /** Light/dark switch is a plain SharedPreferences write, not a Settings change: watch it too. */
    private val themePrefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == COLOR_MODE_KEY || key == AMOLED_DARK_KEY) {
                // Callbacks fire on whichever thread wrote the pref; the overlay must repaint on main.
                mainHandler.post { applyAppearance() }
            }
        }

    /** True while the ball is tucked into the edge (dimmed, half off-screen) waiting to be woken. */
    private var hidden = false

    /** True while the half-screen quick-chat panel is up; the ball stays out of its way. */
    private var panelOpen = false
    private var motionAnimator: ValueAnimator? = null

    private val longPressRunnable = Runnable {
        // Only fire if the finger is still down and we have not turned this into a drag.
        if (ballView != null) {
            longPressFired = true
            openQuickChat(voice = true)
        }
    }

    private val idleRunnable = Runnable {
        if (!panelOpen && ballView != null && !hidden) setHidden(true)
    }

    override fun onCreate() {
        super.onCreate()
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(themePrefsListener)
        // Theme colour / picked icon can change at any time while the ball is up; repaint live.
        // A no-op until the overlay view exists, and showBall() paints once itself.
        scope.launch {
            settingsStore.settingsFlow.collect { applyAppearance() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_PANEL_OPEN -> {
                panelOpen = true
                if (ballView == null) {
                    stopSelf()
                } else {
                    // Out of the way entirely: the panel owns the screen while it is up.
                    mainHandler.removeCallbacks(idleRunnable)
                    motionAnimator?.cancel()
                    ballView?.visibility = View.INVISIBLE
                }
                return START_NOT_STICKY
            }

            ACTION_PANEL_CLOSE -> {
                panelOpen = false
                if (ballView == null) {
                    stopSelf()
                } else {
                    ballView?.visibility = View.VISIBLE
                    // A panel is only ever opened from an awake ball, but stay safe: if it was
                    // tucked away, wake it, and either way restart the idle countdown.
                    if (hidden) setHidden(false) else scheduleIdle()
                }
                return START_NOT_STICKY
            }

            else -> {
                if (!Settings.canDrawOverlays(this)) {
                    Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted — stopping")
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!startForegroundCompat()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                showBall()
                scheduleIdle()
            }
        }
        // NOT sticky: if the user turned the ball off we must not resurrect it after a kill.
        // App start / boot are what bring it back when the setting is still on.
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val view = ballView ?: return
        val params = ballParams ?: return
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        mainHandler.post {
            // Night mode is part of the configuration, so the themed ball may need a repaint.
            applyAppearance()
            if (hidden) {
                // Recompute the tucked position against the new width/orientation.
                tuckIntoEdge()
            } else {
                snapToEdge(wm, view, params)
                scheduleIdle()
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(themePrefsListener)
        }
        hideBall()
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    // ---- Overlay view ----

    @SuppressLint("ClickableViewAccessibility", "RtlHardcoded")
    private fun showBall() {
        if (ballView != null) return
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val density = resources.displayMetrics.density
        val size = (BALL_SIZE_DP * density).toInt()

        val icon = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
        }
        val container = FrameLayout(this).apply {
            contentDescription = getString(R.string.quick_chat_title)
            isClickable = true
            addView(icon)
        }

        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val margin = (12 * density).toInt()
        val defaultX = screenWidth - size - margin
        val defaultY = (screenHeight * 0.62f).toInt()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            size,
            size,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = readStringPreference(PREF_X)?.toIntOrNull() ?: defaultX
            y = readStringPreference(PREF_Y)?.toIntOrNull() ?: defaultY
        }

        try {
            wm.addView(container, params)
        } catch (t: Throwable) {
            Log.w(TAG, "addView failed", t)
            return
        }
        ballView = container
        ballIconView = icon
        ballParams = params
        // Paint colour + icon from the current theme / user picks (never hard-coded).
        applyAppearance()
        attachTouch(container, params, wm)
        // Clamp into range once the view exists — a saved position from another orientation, or
        // a first-run default near the bottom, could otherwise sit partly off-screen.
        mainHandler.post { snapToEdge(wm, container, params) }
    }

    private fun hideBall() {
        val view = ballView ?: return
        ballView = null
        ballIconView = null
        ballParams = null
        longPressFired = false
        hidden = false
        loadedIconPath = null
        loadedIconStamp = 0L
        loadedIconBitmap = null
        mainHandler.removeCallbacks(longPressRunnable)
        mainHandler.removeCallbacks(idleRunnable)
        motionAnimator?.cancel()
        motionAnimator = null
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        runCatching { wm.removeViewImmediate(view) }
            .onFailure { Log.w(TAG, "removeView failed", it) }
    }

    // ---- Appearance ----

    /**
     * Paints the ball from the current settings: a user-picked image fills the whole circle when
     * one is set, otherwise the built-in glyph sits on a circle whose colour follows the app theme
     * (or a user-chosen colour). Cheap and idempotent — safe to call on every settings change.
     */
    private fun applyAppearance() {
        val container = ballView ?: return
        val icon = ballIconView ?: return
        val settings = settingsStore.settingsFlow.value

        val bitmap = settings.floatingBallIconPath
            .takeIf { it.isNotBlank() }
            ?.let { loadIcon(it) }

        if (bitmap != null) {
            // Full-bleed circular crop: the image IS the ball.
            container.clipToOutline = true
            container.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(CUSTOM_ICON_BASE_COLOR)
            }
            icon.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            icon.scaleType = ImageView.ScaleType.CENTER_CROP
            icon.clearColorFilter()
            icon.setImageBitmap(bitmap)
        } else {
            val color = settings.floatingBallColor ?: themePrimaryColor()
            container.clipToOutline = false
            container.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            val iconSize = (BALL_ICON_DP * resources.displayMetrics.density).toInt()
            icon.layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
            icon.scaleType = ImageView.ScaleType.FIT_CENTER
            icon.setColorFilter(
                if (ColorUtils.calculateLuminance(color) > 0.5) 0xFF000000.toInt()
                else 0xFFFFFFFF.toInt()
            )
            icon.setImageResource(R.drawable.ic_floating_ball)
        }
        icon.requestLayout()
    }

    /** The app theme's primary colour, honouring dynamic colour / dark mode / custom themes. */
    private fun themePrimaryColor(): Int = runCatching {
        val settings = settingsStore.settingsFlow.value
        val dark = when (colorMode()) {
            ColorMode.SYSTEM ->
                (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES

            ColorMode.LIGHT -> false
            ColorMode.DARK -> true
        }
        val scheme = if (settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
        } else {
            (findThemeById(settings.themeId, settings.customThemes) ?: findPresetTheme(settings.themeId))
                .getColorScheme(dark)
        }
        scheme.primary.toArgb()
    }.getOrDefault(FALLBACK_BALL_COLOR)

    private fun colorMode(): ColorMode {
        val raw = readStringPreference(COLOR_MODE_KEY)
        return ColorMode.entries.firstOrNull { it.name == raw } ?: ColorMode.SYSTEM
    }

    /** Decodes the picked image, downsampled to roughly the ball size; cached by path + mtime. */
    private fun loadIcon(path: String): Bitmap? {
        val stamp = File(path).lastModified()
        if (loadedIconPath == path && loadedIconStamp == stamp) return loadedIconBitmap
        val bitmap = decodeIcon(path)
        loadedIconPath = path
        loadedIconStamp = stamp
        loadedIconBitmap = bitmap
        return bitmap
    }

    private fun decodeIcon(path: String): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        val target = (BALL_SIZE_DP * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) {
            sample *= 2
        }
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouch(
        view: View,
        params: WindowManager.LayoutParams,
        wm: WindowManager,
    ) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        var waking = false

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Any interaction pushes the auto-tuck countdown back.
                    mainHandler.removeCallbacks(idleRunnable)
                    if (hidden) {
                        // A tucked-away ball only wakes on this touch; it must not also open the
                        // panel or start a long-press.
                        waking = true
                        return@setOnTouchListener true
                    }
                    waking = false
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    longPressFired = false
                    mainHandler.removeCallbacks(longPressRunnable)
                    mainHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (waking) return@setOnTouchListener true
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                    if (dragging) {
                        params.x = (startX + dx).toInt()
                        params.y = (startY + dy).toInt()
                        runCatching { wm.updateViewLayout(view, params) }
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (waking) {
                        waking = false
                        if (event.actionMasked == MotionEvent.ACTION_UP) setHidden(false)
                        return@setOnTouchListener true
                    }
                    when {
                        dragging -> snapToEdge(wm, view, params)
                        !longPressFired -> openQuickChat(voice = false)
                    }
                    dragging = false
                    scheduleIdle()
                    true
                }

                else -> false
            }
        }
    }

    @SuppressLint("RtlHardcoded")
    private fun snapToEdge(wm: WindowManager, view: View, params: WindowManager.LayoutParams) {
        val density = resources.displayMetrics.density
        val margin = (8 * density).toInt()
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val size = view.width.takeIf { it > 0 } ?: params.width
        val maxX = (screenWidth - size - margin).coerceAtLeast(margin)
        val maxY = (screenHeight - size - margin).coerceAtLeast(margin)

        params.x = if (params.x + size / 2 < screenWidth / 2) margin else maxX
        params.y = params.y.coerceIn(margin, maxY)
        runCatching { wm.updateViewLayout(view, params) }
        writeStringPreference(PREF_X, params.x.toString())
        writeStringPreference(PREF_Y, params.y.toString())
    }

    private fun openQuickChat(voice: Boolean) {
        try {
            startActivity(QuickChatActivity.intent(this, voice, ballSide(), ballCenterY()))
        } catch (t: Throwable) {
            Log.w(TAG, "openQuickChat failed", t)
        }
    }

    /** `"left"`/`"right"` — which edge the ball sits on, so the panel can mirror itself. */
    private fun ballSide(): String {
        val params = ballParams ?: return "right"
        val size = ballView?.width?.takeIf { it > 0 } ?: params.width
        val screenWidth = resources.displayMetrics.widthPixels
        return if (params.x + size / 2 >= screenWidth / 2) "right" else "left"
    }

    /** Vertical centre of the ball, in screen pixels; the panel anchors itself to this. */
    private fun ballCenterY(): Int {
        val params = ballParams ?: return 0
        val size = ballView?.height?.takeIf { it > 0 } ?: params.height
        return params.y + size / 2
    }

    // ---- Auto tuck (idle) ----

    private fun idleSeconds(): Int = settingsStore.settingsFlow.value.floatingBallIdleSeconds

    private fun hiddenAlpha(): Float =
        settingsStore.settingsFlow.value.floatingBallHiddenAlpha.coerceIn(10, 100) / 100f

    /** (Re)starts the countdown that tucks the ball away once the user stops bothering with it. */
    private fun scheduleIdle() {
        mainHandler.removeCallbacks(idleRunnable)
        if (panelOpen || hidden || ballView == null) return
        val seconds = idleSeconds()
        if (seconds <= 0) return
        mainHandler.postDelayed(idleRunnable, seconds * 1000L)
    }

    private fun setHidden(hide: Boolean) {
        if (hidden == hide) return
        hidden = hide
        if (hide) {
            mainHandler.removeCallbacks(idleRunnable)
            tuckIntoEdge()
        } else {
            wakeUp()
            scheduleIdle()
        }
    }

    /** Slides the ball half off the edge it was snapped to and dims it. */
    private fun tuckIntoEdge() {
        val view = ballView ?: return
        val params = ballParams ?: return
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val size = view.width.takeIf { it > 0 } ?: params.width
        val screenWidth = resources.displayMetrics.widthPixels
        val onRight = params.x + size / 2 >= screenWidth / 2
        val visible = (size * HIDDEN_VISIBLE_FRACTION).toInt().coerceAtLeast(1)
        val targetX = if (onRight) (screenWidth - visible).toFloat() else (visible - size).toFloat()
        animateTo(view, params, wm, targetX, hiddenAlpha())
    }

    /** Brings a tucked-away ball back out to its remembered edge, at full opacity. */
    private fun wakeUp() {
        val view = ballView ?: return
        val params = ballParams ?: return
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val density = resources.displayMetrics.density
        val size = view.width.takeIf { it > 0 } ?: params.width
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val margin = (8 * density).toInt()
        val maxX = (screenWidth - size - margin).coerceAtLeast(margin)
        val maxY = (screenHeight - size - margin).coerceAtLeast(margin)
        params.y = params.y.coerceIn(margin, maxY)
        val restingX = readStringPreference(PREF_X)?.toIntOrNull()?.coerceIn(margin, maxX)
            ?: if (params.x + size / 2 >= screenWidth / 2) maxX else margin
        animateTo(view, params, wm, restingX.toFloat(), 1f)
        writeStringPreference(PREF_X, restingX.toString())
        writeStringPreference(PREF_Y, params.y.toString())
    }

    private fun animateTo(
        view: View,
        params: WindowManager.LayoutParams,
        wm: WindowManager,
        targetX: Float,
        targetAlpha: Float,
    ) {
        motionAnimator?.cancel()
        val startX = params.x.toFloat()
        val startAlpha = view.alpha
        motionAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = IDLE_ANIM_MS
            addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float
                params.x = Math.round(startX + (targetX - startX) * fraction)
                view.alpha = startAlpha + (targetAlpha - startAlpha) * fraction
                runCatching { wm.updateViewLayout(view, params) }
            }
            start()
        }
    }

    // ---- Foreground ----

    private fun startForegroundCompat(): Boolean {
        return try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            // Some OEM ROMs reject the specialUse FGS type even with the manifest declaration.
            Log.e(TAG, "Failed to start foreground service", e)
            false
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val stopIntent = Intent(this, FloatingBallService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, FLOATING_BALL_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(getString(R.string.notification_floating_ball_running))
            .setContentText(getString(R.string.notification_floating_ball_hint))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .apply { contentIntent?.let { setContentIntent(it) } }
            .addAction(0, getString(R.string.notification_floating_ball_stop), stopPendingIntent)
            .build()
    }
}
