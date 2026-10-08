package me.rerere.rikkahub.ui.pages.quickchat

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.view.WindowCompat
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
 */
class QuickChatActivity : ComponentActivity() {

    companion object {
        const val EXTRA_VOICE = "quick_chat_voice"

        fun intent(context: Context, voice: Boolean): Intent =
            Intent(context, QuickChatActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_VOICE, voice)
            }
    }

    // Monotonic so a long-press while the panel is already open re-triggers the recorder even
    // though the activity is not recreated (singleTop -> onNewIntent).
    private val voiceSignal = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (intent?.getBooleanExtra(EXTRA_VOICE, false) == true) {
            voiceSignal.intValue = 1
        }
        setContent {
            RikkahubTheme {
                QuickChatPanel(
                    startVoiceSignal = voiceSignal.intValue,
                    onDismiss = { finish() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_VOICE, false)) {
            voiceSignal.intValue += 1
        }
    }
}
