package me.rerere.rikkahub.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import me.rerere.rikkahub.FLOATING_BALL_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.hooks.readStringPreference
import me.rerere.rikkahub.ui.hooks.writeStringPreference
import me.rerere.rikkahub.ui.pages.quickchat.QuickChatActivity
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
class FloatingBallService : Service() {

    companion object {
        const val ACTION_START = "me.rerere.rikkahub.action.FLOATING_BALL_START"
        const val ACTION_STOP = "me.rerere.rikkahub.action.FLOATING_BALL_STOP"
        const val NOTIFICATION_ID = 2003

        private const val PREF_X = "floating_ball_x"
        private const val PREF_Y = "floating_ball_y"
        private const val LONG_PRESS_MS = 420L
        private const val BALL_SIZE_DP = 56
        private const val BALL_ICON_DP = 28
        private const val BALL_COLOR = 0xFF6750A4.toInt()

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
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var ballView: FrameLayout? = null
    private var ballParams: WindowManager.LayoutParams? = null
    private var longPressFired = false

    private val longPressRunnable = Runnable {
        // Only fire if the finger is still down and we have not turned this into a drag.
        if (ballView != null) {
            longPressFired = true
            openQuickChat(voice = true)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
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
        mainHandler.post { snapToEdge(wm, view, params) }
    }

    override fun onDestroy() {
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
        val iconSize = (BALL_ICON_DP * density).toInt()

        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_floating_ball)
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
        }
        val container = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(BALL_COLOR)
            }
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
        ballParams = params
        attachTouch(container, params, wm)
        // Clamp into range once the view exists — a saved position from another orientation, or
        // a first-run default near the bottom, could otherwise sit partly off-screen.
        mainHandler.post { snapToEdge(wm, container, params) }
    }

    private fun hideBall() {
        val view = ballView ?: return
        ballView = null
        ballParams = null
        longPressFired = false
        mainHandler.removeCallbacks(longPressRunnable)
        val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        runCatching { wm.removeViewImmediate(view) }
            .onFailure { Log.w(TAG, "removeView failed", it) }
    }

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

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
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
                    when {
                        dragging -> snapToEdge(wm, view, params)
                        !longPressFired -> openQuickChat(voice = false)
                    }
                    dragging = false
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
            startActivity(QuickChatActivity.intent(this, voice))
        } catch (t: Throwable) {
            Log.w(TAG, "openQuickChat failed", t)
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
