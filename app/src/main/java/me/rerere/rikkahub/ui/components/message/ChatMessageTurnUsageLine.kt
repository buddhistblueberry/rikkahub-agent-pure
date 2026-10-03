package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.CoinsDollar
import me.rerere.hugeicons.stroke.Cpu
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Upload02
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.usage.TurnUsageView
import me.rerere.rikkahub.data.usage.UsageCallView
import me.rerere.rikkahub.data.usage.UsagePurpose
import me.rerere.rikkahub.utils.formatNumber
import kotlin.math.roundToInt

/**
 * P2-12b — the turn view, shown in place of the single-call footer when the ledger has rows for
 * this turn. Collapsed it is one summary line (`N calls` + totals); tapping it expands the
 * per-call list. Falls back to the old single-call footer whenever there are no rows.
 */
@Composable
internal fun TurnUsageLine(
    turnUsage: TurnUsageView,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier.padding(horizontal = 4.dp)) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { expanded = !expanded },
        ) {
            Text(text = stringResource(R.string.chat_message_turn_calls, turnUsage.callCount))
            StatsItem(
                icon = {
                    Icon(
                        imageVector = HugeIcons.Upload02,
                        contentDescription = stringResource(R.string.accessibility_input_tokens),
                        modifier = Modifier.size(12.dp),
                    )
                },
                content = { Text(text = "${turnUsage.inputTokens.tokensText()} tokens") },
            )
            // The split is only meaningful when at least one call reported cache fields; a
            // provider that stays silent must not be rendered as a 0% hit rate.
            if (turnUsage.cacheReported && turnUsage.cachePromptTokens > 0) {
                val hit = turnUsage.cacheHitTokens.coerceAtMost(turnUsage.cachePromptTokens)
                val miss = turnUsage.cachePromptTokens - hit
                val rate = hit * 100 / turnUsage.cachePromptTokens
                Text(text = "(${hit.tokensText()} hit / ${miss.tokensText()} miss / $rate%)")
            }
            StatsItem(
                icon = {
                    Icon(
                        imageVector = HugeIcons.Download04,
                        contentDescription = stringResource(R.string.accessibility_output_tokens),
                        modifier = Modifier.size(12.dp),
                    )
                },
                content = { Text(text = "${turnUsage.outputTokens.tokensText()} tokens") },
            )
            turnUsage.tokensPerSecond?.let { rate ->
                StatsItem(
                    icon = {
                        Icon(
                            imageVector = HugeIcons.Cpu,
                            contentDescription = stringResource(R.string.chat_message_turn_speed),
                            modifier = Modifier.size(12.dp),
                        )
                    },
                    content = { Text(text = "${rate.roundToInt()} tok/s") },
                )
            }
            turnCostText(turnUsage.providerCostUsd, turnUsage.costMicros)?.let { cost ->
                StatsItem(
                    icon = {
                        Icon(
                            imageVector = HugeIcons.CoinsDollar,
                            contentDescription = stringResource(R.string.accessibility_cost),
                            modifier = Modifier.size(12.dp),
                        )
                    },
                    content = { Text(text = cost) },
                )
            }
            Icon(
                imageVector = if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                contentDescription = stringResource(
                    if (expanded) R.string.chat_message_turn_collapse
                    else R.string.chat_message_turn_expand
                ),
                modifier = Modifier.size(12.dp),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier.padding(top = 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                turnUsage.calls.forEach { call -> UsageCallRow(call) }
            }
        }
    }
}

@Composable
private fun UsageCallRow(call: UsageCallView) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(usagePurposeLabel(call.purpose)),
            modifier = Modifier.padding(end = 2.dp),
        )
        Text(text = "${call.inputTokens.formatNumber()} → ${call.outputTokens.formatNumber()}")
        if (call.cachedTokensReported && call.cachedTokens > 0) {
            Text(text = "(${call.cachedTokens.formatNumber()} hit)")
        }
        turnCostText(call.providerCostUsd, call.costMicros)?.let { Text(text = it) }
        call.latencyMs?.let { ms ->
            Text(text = "${ms}ms")
            if (ms > 0) Text(text = "${(call.outputTokens * 1000.0 / ms).roundToInt()} tok/s")
        }
    }
}

/**
 * `~` marks a cost the local price table computed; a provider-reported cost is shown bare.
 * Unpriced calls render nothing rather than "$0" (P2-11: a missing price is not free).
 */
private fun turnCostText(providerCostUsd: Double?, costMicros: Long?): String? {
    if (providerCostUsd != null && providerCostUsd > 0.0) return formatCost(providerCostUsd)
    if (costMicros != null && costMicros > 0L) return "~" + formatCost(costMicros / 1_000_000.0)
    return null
}

private fun usagePurposeLabel(purpose: String): Int = when (purpose) {
    UsagePurpose.MAIN.name -> R.string.chat_message_usage_purpose_main
    UsagePurpose.TOOL_LOOP.name -> R.string.chat_message_usage_purpose_tool_loop
    UsagePurpose.COMPACTION.name -> R.string.chat_message_usage_purpose_compaction
    UsagePurpose.MEMORY_EXTRACT.name -> R.string.chat_message_usage_purpose_memory_extract
    UsagePurpose.SUBAGENT.name -> R.string.chat_message_usage_purpose_subagent
    UsagePurpose.CRON.name -> R.string.chat_message_usage_purpose_cron
    UsagePurpose.WORKFLOW.name -> R.string.chat_message_usage_purpose_workflow
    UsagePurpose.SKILL_TEST.name -> R.string.chat_message_usage_purpose_skill_test
    UsagePurpose.TRANSLATION.name -> R.string.chat_message_usage_purpose_translation
    else -> R.string.chat_message_usage_purpose_unknown
}

/** `formatNumber()` is Int-only; turn totals are Long, so clamp before formatting. */
private fun Long.tokensText(): String =
    if (this in 0..Int.MAX_VALUE.toLong()) toInt().formatNumber() else toString()
