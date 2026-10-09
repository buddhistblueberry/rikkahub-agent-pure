package me.rerere.rikkahub.ui.pages.quickchat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import me.rerere.rikkahub.service.FloatingBallService
import me.rerere.rikkahub.ui.theme.RikkahubTheme

/**
 * Half-screen quick-chat panel opened by the floating ball.
 *
 * Transparent + translucent so whatever app the user is in stays visible behind the panel — the
 * panel looks like it floats over the current screen, and dismissing it (tap outside / close /
 * after sending a screen-action request) returns straight to that app with no task switch.
 *
 * Reuses the app's own theme and Koin singletons, so the panel talks to the same ChatService
 * conversations as the main chat page.
 *
 * The panel is positioned and mirrored from the ball that opened it: it never sits at a fixed
 * bottom, and its controls move to whichever side the ball is on (see [QuickChatPanel]).
 */
class QuickChatActivity : ComponentActivity() {

    companion object {
        /**
         * True when the panel was opened by holding the ball: the panel records for exactly as
         * long as the ball stays held (see [FloatingBallService.voiceHoldActive]).
         */
        const val EXTRA_VOICE = "quick_chat_voice"

        /** Which edge the ball sits on, `"left"` / `"right"`; drives the panel's mirrored layout. */
        const val EXTRA_BALL_SIDE = "quick_chat_ball_side"

        /** Vertical centre of the ball, in screen pixels; the panel anchors itself to it. */
        const val EXTRA_BALL_CENTER_Y = "quick_chat_ball_center_y"

        fun intent(context: Context, voice: Boolean, ballSide: String, ballCenterY: Int): Intent =
            Intent(context, QuickChatActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        // The panel animates itself (it unfolds out of the ball); the platform's
                        // stock open/close transition layered on top was the stutter — worst over
                        // the launcher. Belt-and-braces with the activity's own override below.
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                putExtra(EXTRA_VOICE, voice)
                putExtra(EXTRA_BALL_SIDE, ballSide)
                putExtra(EXTRA_BALL_CENTER_Y, ballCenterY)
            }
    }

    /** Mirror the panel when the ball is on the left, so its controls fall under that thumb. */
    private val mirror = mutableStateOf(false)
    private val ballCenterY = mutableIntStateOf(0)

    /** Opened by holding the ball: the panel records for as long as the ball stays held. */
    private val holdToTalk = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        noSystemTransition()
        readPanelExtras(intent)
        setContent {
            RikkahubTheme {
                QuickChatPanel(
                    holdToTalk = holdToTalk.value,
                    mirror = mirror.value,
                    ballCenterY = ballCenterY.intValue,
                    // Told from the panel's first frame, not from onCreate: the ball folds itself
                    // away exactly as the panel unfolds, and stands the idle countdown down while
                    // the panel owns the screen. It comes back when we finish.
                    onUnfoldStart = { FloatingBallService.notifyPanel(this, open = true) },
                    onClose = { finish() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readPanelExtras(intent)
    }

    override fun onDestroy() {
        FloatingBallService.notifyPanel(this, open = false)
        super.onDestroy()
    }

    /**
     * The panel runs its own unfold/fold, so the platform transition layered on top of it is pure
     * jitter (and, over the launcher, a visibly half-played one). The launch intent already carries
     * FLAG_ACTIVITY_NO_ANIMATION; this covers the close half and Android 14+ as well.
     */
    private fun noSystemTransition() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
    }

    override fun finish() {
        super.finish()
        // The panel folds back into the ball itself; the stock close animation would fight it.
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun readPanelExtras(intent: Intent?) {
        mirror.value = intent?.getStringExtra(EXTRA_BALL_SIDE) == "left"
        ballCenterY.intValue = intent?.getIntExtra(EXTRA_BALL_CENTER_Y, 0) ?: 0
        holdToTalk.value = intent?.getBooleanExtra(EXTRA_VOICE, false) == true
    }
}
