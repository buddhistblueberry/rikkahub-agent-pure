package me.rerere.rikkahub.ui.pages.setting

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.FloatingBallService
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

/**
 * Settings for the always-available floating ball: master switch, the SYSTEM_ALERT_WINDOW grant
 * it depends on, and a short explanation of the gestures.
 */
@Composable
fun SettingFloatingBallPage(vm: SettingVM = koinViewModel()) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var showIdleDialog by remember { mutableStateOf(false) }
    // Draft so dragging the slider does not write DataStore on every frame; committed on release.
    var alphaDraft by remember(settings.floatingBallHiddenAlpha) {
        mutableStateOf(settings.floatingBallHiddenAlpha.toFloat())
    }

    fun overlaySettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }

    fun applyEnabled(enabled: Boolean) {
        vm.updateSettings { it.copy(floatingBallEnabled = enabled) }
        if (!enabled) {
            FloatingBallService.stop(context)
            return
        }
        if (Settings.canDrawOverlays(context)) {
            FloatingBallService.start(context)
        } else {
            // Can't show anything without the grant: send the user straight to it. Returning to
            // this page re-checks and starts the ball (see the resume observer below).
            context.startActivity(overlaySettingsIntent())
        }
    }

    // Re-check the grant on resume (the user comes back from the system settings page) and, if
    // the switch is on, start the ball now that it can actually be shown.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, settings.floatingBallEnabled) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = Settings.canDrawOverlays(context)
                if (overlayGranted && settings.floatingBallEnabled) {
                    FloatingBallService.start(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_floating_ball_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup(modifier = Modifier.padding(horizontal = 8.dp)) {
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_floating_ball_enable_title))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_floating_ball_enable_desc))
                        },
                        trailingContent = {
                            Switch(
                                checked = settings.floatingBallEnabled,
                                onCheckedChange = { applyEnabled(it) },
                            )
                        },
                    )
                    item(
                        onClick = {
                            if (!overlayGranted) context.startActivity(overlaySettingsIntent())
                        },
                        headlineContent = {
                            Text(stringResource(R.string.setting_floating_ball_overlay_title))
                        },
                        supportingContent = {
                            Text(
                                stringResource(
                                    if (overlayGranted) {
                                        R.string.setting_floating_ball_overlay_granted
                                    } else {
                                        R.string.setting_floating_ball_overlay_required
                                    }
                                )
                            )
                        },
                        trailingContent = {
                            if (!overlayGranted) {
                                TextButton(onClick = { context.startActivity(overlaySettingsIntent()) }) {
                                    Text(stringResource(R.string.setting_floating_ball_overlay_grant))
                                }
                            }
                        },
                    )
                }
            }

            item {
                CardGroup(modifier = Modifier.padding(horizontal = 8.dp)) {
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_floating_ball_gestures_title))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_floating_ball_gestures_desc))
                        },
                    )
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.quick_chat_look_screen))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_floating_ball_screen_action_hint))
                        },
                    )
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_floating_ball_voice_title))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_floating_ball_asr_hint))
                        },
                    )
                }
            }

            item {
                CardGroup(modifier = Modifier.padding(horizontal = 8.dp)) {
                    item(
                        onClick = { showIdleDialog = true },
                        headlineContent = {
                            Text(stringResource(R.string.setting_floating_ball_idle_title))
                        },
                        supportingContent = {
                            Text(stringResource(R.string.setting_floating_ball_idle_desc))
                        },
                        trailingContent = {
                            TextButton(onClick = { showIdleDialog = true }) {
                                Text(idleSecondsLabel(settings.floatingBallIdleSeconds))
                            }
                        },
                    )
                    item(
                        headlineContent = {
                            Text(stringResource(R.string.setting_floating_ball_alpha_title))
                        },
                        supportingContent = {
                            Column {
                                Text(stringResource(R.string.setting_floating_ball_alpha_desc))
                                Slider(
                                    value = alphaDraft,
                                    onValueChange = { alphaDraft = it },
                                    onValueChangeFinished = {
                                        vm.updateSettings {
                                            it.copy(floatingBallHiddenAlpha = alphaDraft.roundToInt())
                                        }
                                    },
                                    valueRange = 20f..100f,
                                    steps = 7,
                                )
                            }
                        },
                        trailingContent = {
                            Text("${alphaDraft.roundToInt()}%")
                        },
                    )
                }
            }

            item {
                Text(
                    text = stringResource(R.string.setting_floating_ball_notes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }

    if (showIdleDialog) {
        AlertDialog(
            onDismissRequest = { showIdleDialog = false },
            title = { Text(stringResource(R.string.setting_floating_ball_idle_title)) },
            text = {
                Column {
                    IDLE_SECONDS_OPTIONS.forEach { option ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.updateSettings {
                                        it.copy(floatingBallIdleSeconds = option)
                                    }
                                    showIdleDialog = false
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = settings.floatingBallIdleSeconds == option,
                                onClick = {
                                    vm.updateSettings {
                                        it.copy(floatingBallIdleSeconds = option)
                                    }
                                    showIdleDialog = false
                                },
                            )
                            Text(idleSecondsLabel(option))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showIdleDialog = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }
}

/** `0` disables the auto-tuck; anything else is a wait, in seconds. */
private val IDLE_SECONDS_OPTIONS = listOf(0, 2, 3, 5, 10)

@Composable
private fun idleSecondsLabel(seconds: Int): String =
    if (seconds <= 0) {
        stringResource(R.string.setting_floating_ball_idle_off)
    } else {
        stringResource(R.string.setting_floating_ball_idle_seconds, seconds)
    }
