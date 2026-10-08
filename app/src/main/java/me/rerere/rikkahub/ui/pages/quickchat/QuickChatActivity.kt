package me.rerere.rikkahub.ui.pages.quickchat

import android.content.Context
import android.content.Intent
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
        const val EXTRA_VOICE = "quick_chat_voice"

        /** Which edge the ball sits on, `"left"` / `"right"`; drives the panel's mirrored layout. */
        const val EXTRA_BALL_SIDE = "quick_chat_ball_side"

        /** Vertical centre of the ball, in screen pixels; the panel anchors itself to it. */
        const val EXTRA_BALL_CENTER_Y = "quick_chat_ball_center_y"

        fun intent(context: Context, voice: Boolean, ballSide: String, ballCenterY: Int): Intent =
            Intent(context, QuickChatActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_VOICE, voice)
                putExtra(EXTRA_BALL_SIDE, ballSide)
                putExtra(EXTRA_BALL_CENTER_Y, ballCenterY)
            }
    }

    // Monotonic so a long-press while the panel is already open re-triggers the recorder even
    // though the activity is not recreated (singleTop -> onNewIntent).
    private val voiceSignal = mutableIntStateOf(0)

    /** Mirror the panel when the ball is on the left, so its controls fall under that thumb. */
    private val mirror = mutableStateOf(false)
    private val ballCenterY = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // The ball steps aside while the panel owns the screen; it returns when we finish.
        FloatingBallService.notifyPanel(this, open = true)
        readBallPosition(intent)
        if (intent?.getBooleanExtra(EXTRA_VOICE, false) == true) {
            voiceSignal.intValue = 1
        }
        setContent {
            RikkahubTheme {
                QuickChatPanel(
                    startVoiceSignal = voiceSignal.intValue,
                    mirror = mirror.value,
                    ballCenterY = ballCenterY.intValue,
                    onDismiss = { finish() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readBallPosition(intent)
        if (intent.getBooleanExtra(EXTRA_VOICE, false)) {
            voiceSignal.intValue += 1
        }
    }

    override fun onDestroy() {
        FloatingBallService.notifyPanel(this, open = false)
        super.onDestroy()
    }

    private fun readBallPosition(intent: Intent?) {
        mirror.value = intent?.getStringExtra(EXTRA_BALL_SIDE) == "left"
        ballCenterY.intValue = intent?.getIntExtra(EXTRA_BALL_CENTER_Y, 0) ?: 0
    }
}
