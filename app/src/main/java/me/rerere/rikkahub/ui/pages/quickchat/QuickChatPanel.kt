package me.rerere.rikkahub.ui.pages.quickchat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.LookTop
import me.rerere.hugeicons.stroke.Mic01
import me.rerere.hugeicons.stroke.PlusSign
import me.rerere.hugeicons.stroke.Stop
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getSelectedASRProvider
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.hooks.readStringPreference
import me.rerere.rikkahub.ui.hooks.rememberCustomAsrState
import org.koin.compose.koinInject

private const val LAST_CONVERSATION_KEY = "lastConversationId"

/** Full panel height, as a fraction of the available screen height. */
private const val PANEL_HEIGHT_FRACTION = 0.62f

/** The ball sits this far down the panel, so the panel mostly unfolds above the ball. */
private const val BALL_ANCHOR_FRACTION = 0.75f

/**
 * The panel shrinks its message list when a low ball would push it off the bottom, but never
 * below this fraction of its full height — past that it just rests on the bottom edge instead.
 */
private const val PANEL_MIN_HEIGHT_FRACTION = 0.75f

/**
 * Reuse the conversation the user was last in; fall back to a fresh one on first use. The main
 * chat page writes `lastConversationId` (ChatVM) so this stays in sync with normal use.
 */
@OptIn(ExperimentalUuidApi::class)
private fun initialConversationId(context: Context): Uuid {
    val saved = context.readStringPreference(LAST_CONVERSATION_KEY)
    return saved?.let { runCatching { Uuid.parse(it) }.getOrNull() } ?: Uuid.random()
}

/**
 * The panel itself: latest messages of the active conversation, a quick-action for screen
 * automation, and a text/voice composer.
 *
 * Sending posts straight through [ChatService.sendMessage] — the same path as the in-app chat,
 * so approvals, cost guards and token accounting apply unchanged. The screen-action button sends
 * and immediately dismisses the panel so the agent can see and drive the app underneath; a plain
 * send keeps the panel open so the reply streams in view.
 *
 * The panel is not pinned to the bottom: it is placed so the ball that opened it lands at
 * [BALL_ANCHOR_FRACTION] of the panel's height, mirrored horizontally when the ball is on the
 * left so the controls sit under the user's thumb. When the ball is too low for the full panel to
 * fit, the message area gives up space (at most 25%) before the panel rests on the bottom edge.
 */
@OptIn(ExperimentalUuidApi::class)
@Composable
fun QuickChatPanel(
    startVoiceSignal: Int,
    mirror: Boolean,
    ballCenterY: Int,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val chatService = koinInject<ChatService>()
    val settingsStore = koinInject<SettingsStore>()

    var conversationId by remember { mutableStateOf(initialConversationId(context)) }
    val conversation by chatService
        .getConversationFlow(conversationId)
        .collectAsStateWithLifecycle()
    val processingStatus by chatService
        .getProcessingStatusFlow(conversationId)
        .collectAsStateWithLifecycle()
    val isGenerating = processingStatus != null

    LaunchedEffect(conversationId) {
        chatService.initializeConversation(conversationId)
    }

    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val hasAsrProvider = settings.getSelectedASRProvider() != null

    var input by remember { mutableStateOf("") }
    val asrState = rememberCustomAsrState()
    val asr by asrState.state.collectAsStateWithLifecycle()

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            asrState.start { input = it }
        } else {
            Toast.makeText(context, R.string.quick_chat_mic_permission, Toast.LENGTH_SHORT).show()
        }
    }

    fun startVoice() {
        if (!hasAsrProvider) {
            Toast.makeText(context, R.string.quick_chat_no_asr, Toast.LENGTH_SHORT).show()
            return
        }
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            asrState.start { input = it }
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(startVoiceSignal) {
        if (startVoiceSignal > 0) startVoice()
    }

    val screenPrompt = stringResource(R.string.quick_chat_look_screen_prompt)
    val fallbackTitle = stringResource(R.string.quick_chat_title)

    fun submit(text: String, closeAfterSend: Boolean) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        asrState.stop()
        chatService.sendMessage(conversationId, listOf(UIMessagePart.Text(trimmed)))
        input = ""
        if (closeAfterSend) onDismiss()
    }

    // The interactive controls, split into individual buttons so mirroring is an explicit,
    // predictable re-order (no RTL layout direction, which would also right-align the text field).
    val newButton: @Composable () -> Unit = {
        IconButton(onClick = {
            asrState.stop()
            input = ""
            conversationId = Uuid.random()
        }) {
            Icon(HugeIcons.PlusSign, stringResource(R.string.quick_chat_new))
        }
    }
    val openFullButton: @Composable () -> Unit = {
        IconButton(onClick = {
            val intent = Intent(context, RouteActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("conversationId", conversationId.toString())
            }
            context.startActivity(intent)
            onDismiss()
        }) {
            Icon(HugeIcons.ArrowRight01, stringResource(R.string.quick_chat_open_full))
        }
    }
    val closeButton: @Composable () -> Unit = {
        IconButton(onClick = onDismiss) {
            Icon(HugeIcons.Cancel01, stringResource(R.string.quick_chat_close))
        }
    }
    val micButton: @Composable () -> Unit = {
        IconButton(onClick = {
            if (asr.isRecording) asrState.stop() else startVoice()
        }) {
            Icon(HugeIcons.Mic01, stringResource(R.string.quick_chat_voice))
        }
    }
    val sendButton: @Composable () -> Unit = {
        if (isGenerating) {
            IconButton(onClick = {
                scope.launch { chatService.stopGeneration(conversationId) }
            }) {
                Icon(HugeIcons.Stop, stringResource(R.string.quick_chat_stop))
            }
        } else {
            FilledIconButton(
                onClick = { submit(input, false) },
                enabled = input.isNotBlank(),
            ) {
                Icon(HugeIcons.ArrowUp01, stringResource(R.string.quick_chat_send))
            }
        }
    }

    val messages = conversation.currentMessages.filter {
        it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT
    }
    val listState = rememberLazyListState()
    LaunchedEffect(conversationId, messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        val density = LocalDensity.current
        val fullHeight = maxHeight * PANEL_HEIGHT_FRACTION
        val minHeight = fullHeight * PANEL_MIN_HEIGHT_FRACTION
        // Place the panel so the ball lands at BALL_ANCHOR_FRACTION of its height:
        // top = ballY - anchor * height, and we need top + height <= maxHeight.
        val ballY = with(density) { ballCenterY.toDp() }
        val heightForBall = (maxHeight - ballY) / (1f - BALL_ANCHOR_FRACTION)
        val panelHeight = heightForBall.coerceIn(minHeight, fullHeight)
        val panelTop = (ballY - panelHeight * BALL_ANCHOR_FRACTION)
            .coerceAtMost(maxHeight - panelHeight)
            .coerceAtLeast(0.dp)

        // Tap anywhere off the panel to dismiss it; the app behind shows through the gap.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismiss() }
        )

        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset { IntOffset(0, with(density) { panelTop.roundToPx() }) }
                .fillMaxWidth()
                .height(panelHeight)
                // Swallow taps on the panel's own surface (padding, empty history) so they do
                // not fall through to the dismiss layer behind it.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (mirror) {
                        closeButton()
                        openFullButton()
                        newButton()
                    }
                    Text(
                        text = conversation.title.ifBlank { fallbackTitle },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (!mirror) {
                        newButton()
                        openFullButton()
                        closeButton()
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (messages.isEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.quick_chat_empty),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(messages, key = { it.id.toString() }) { message ->
                        MessageBubble(message)
                    }
                }

                if (asr.isRecording) {
                    StatusRow(text = stringResource(R.string.quick_chat_listening))
                } else if (isGenerating) {
                    StatusRow(text = processingStatus.orEmpty())
                }

                FilledTonalButton(
                    onClick = { submit(screenPrompt, closeAfterSend = true) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(HugeIcons.LookTop, null)
                    Text(
                        text = stringResource(R.string.quick_chat_look_screen),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }

                Row(verticalAlignment = Alignment.Bottom) {
                    if (mirror) {
                        sendButton()
                        micButton()
                    }
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.quick_chat_input_hint)) },
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { submit(input, false) }),
                    )
                    if (!mirror) {
                        micButton()
                        sendButton()
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun MessageBubble(message: UIMessage) {
    val isUser = message.role == MessageRole.USER
    val text = message.parts
        .filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .trim()
    if (text.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f),
            shape = RoundedCornerShape(16.dp),
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}
