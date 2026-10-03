package me.rerere.rikkahub.ui.pages.stats

import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ChartColumn
import me.rerere.hugeicons.stroke.Cpu
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.Rocket01
import me.rerere.hugeicons.stroke.Zap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.usage.LedgerStatsView
import me.rerere.rikkahub.data.usage.OrchestrationNode
import me.rerere.rikkahub.data.usage.OrchestrationTree
import me.rerere.rikkahub.data.usage.UsagePurpose
import me.rerere.rikkahub.data.usage.UsageStatBucket
import me.rerere.rikkahub.data.usage.UsageStatsFactory
import me.rerere.rikkahub.ui.components.message.formatCost
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

@Composable
fun StatsPage(vm: StatsVM = koinViewModel()) {
    val stats by vm.stats.collectAsStateWithLifecycle()
    val ledgerStats by vm.ledgerStats.collectAsStateWithLifecycle()
    val assistantNames by vm.assistantNames.collectAsStateWithLifecycle()
    val orchestrationTrees by vm.orchestrationTrees.collectAsStateWithLifecycle()
    val conversationTitles by vm.conversationTitles.collectAsStateWithLifecycle()

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.stats_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        if (stats.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = padding + PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    HeatmapCard(
                        conversationsPerDay = stats.conversationsPerDay,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                item {
                    StatsGrid(
                        stats = stats,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
                ledgerStats?.let { ledger ->
                    item {
                        LedgerStatsSection(
                            view = ledger,
                            assistantNames = assistantNames,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
                orchestrationTrees?.let { trees ->
                    item {
                        OrchestrationSection(
                            trees = trees,
                            conversationTitles = conversationTitles,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HeatmapCard(conversationsPerDay: Map<LocalDate, Int>, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.stats_page_heatmap_title), style = MaterialTheme.typography.titleMedium)

            ChatHeatmap(conversationsPerDay = conversationsPerDay)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.stats_page_heatmap_less),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(2.dp))
                listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { alpha ->
                    HeatmapCell(alpha = alpha, sizeDp = 10)
                }
                Spacer(Modifier.width(2.dp))
                Text(
                    text = stringResource(R.string.stats_page_heatmap_more),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChatHeatmap(conversationsPerDay: Map<LocalDate, Int>) {
    val today = LocalDate.now()
    val startSunday = today
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        .minusWeeks(52)

    val numWeeks = 53
    val activeCounts = conversationsPerDay.values.filter { it > 0 }.sorted()
    val q1 = activeCounts.getOrElse((activeCounts.size * 0.25).toInt()) { 1 }
    val q2 = activeCounts.getOrElse((activeCounts.size * 0.50).toInt()) { 2 }
    val q3 = activeCounts.getOrElse((activeCounts.size * 0.75).toInt()) { 3 }
    val cellSize = 11.dp
    val cellSpacing = 2.dp
    // Month label row height
    val monthLabelHeight = 14.dp

    // Day-of-week labels (only Mon/Wed/Fri to save space, Sun=0)
    val dowLabels = listOf(
        "",
        stringResource(R.string.stats_page_dow_mon),
        "",
        stringResource(R.string.stats_page_dow_wed),
        "",
        stringResource(R.string.stats_page_dow_fri),
        ""
    )

    // Shared scroll state so month labels + grid scroll together
    val scrollState = rememberScrollState(initial = Int.MAX_VALUE)

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        // Fixed left column: spacer for month label row + DOW labels
        Column(
            modifier = Modifier.width(12.dp),
            verticalArrangement = Arrangement.spacedBy(cellSpacing),
        ) {
            Spacer(Modifier.height(monthLabelHeight + 2.dp))
            dowLabels.forEach { label ->
                Box(
                    modifier = Modifier.size(cellSize),
                    contentAlignment = Alignment.Center,
                ) {
                    if (label.isNotEmpty()) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.7,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Scrollable area: month labels + heatmap grid share one scroll state
        Column(
            modifier = Modifier.horizontalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // Month labels row
            Row(horizontalArrangement = Arrangement.spacedBy(cellSpacing)) {
                for (weekIdx in 0 until numWeeks) {
                    val weekStart = startSunday.plusDays((weekIdx * 7).toLong())
                    val labelDate = (0..6)
                        .map { weekStart.plusDays(it.toLong()) }
                        .firstOrNull { it.dayOfMonth == 1 }
                    Box(
                        modifier = Modifier
                            .width(cellSize)
                            .height(monthLabelHeight),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        if (labelDate != null) {
                            Text(
                                text = if (labelDate.monthValue == 1) {
                                    labelDate.year.toString()
                                } else {
                                    labelDate.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                                },
                                modifier = Modifier.wrapContentWidth(unbounded = true),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.75,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                softWrap = false,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }

            // Heatmap grid
            Row(horizontalArrangement = Arrangement.spacedBy(cellSpacing)) {
                for (weekIdx in 0 until numWeeks) {
                    Column(verticalArrangement = Arrangement.spacedBy(cellSpacing)) {
                        for (dow in 0..6) {
                            val date = startSunday.plusDays((weekIdx * 7 + dow).toLong())
                            val isFuture = date.isAfter(today)
                            val count = if (isFuture) 0 else (conversationsPerDay[date] ?: 0)
                            val alpha = when {
                                isFuture -> -1f
                                count == 0 -> 0f
                                count <= q1 -> 0.25f
                                count <= q2 -> 0.5f
                                count <= q3 -> 0.75f
                                else -> 1f
                            }
                            HeatmapCell(alpha = alpha, sizeDp = cellSize.value.toInt())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeatmapCell(alpha: Float, sizeDp: Int) {
    val color = when {
        alpha < 0f -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f) // future
        alpha == 0f -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.primary.copy(alpha = alpha)
    }
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(color)
    )
}

@Composable
private fun StatsGrid(stats: AppStats, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                icon = HugeIcons.ChartColumn,
                label = stringResource(R.string.stats_page_total_conversations),
                value = formatCount(stats.totalConversations.toLong()),
            )
            StatCard(
                modifier = Modifier.weight(1f),
                icon = HugeIcons.Message01,
                label = stringResource(R.string.stats_page_total_messages),
                value = formatCount(stats.totalMessages.toLong()),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            StatCard(
                modifier = Modifier.weight(1f),
                icon = HugeIcons.Cpu,
                label = stringResource(R.string.stats_page_input_tokens),
                value = formatTokens(stats.totalPromptTokens),
            )
            StatCard(
                modifier = Modifier.weight(1f),
                icon = HugeIcons.Cpu,
                label = stringResource(R.string.stats_page_output_tokens),
                value = formatTokens(stats.totalCompletionTokens),
            )
        }
        if (stats.totalCachedTokens > 0) {
            StatCard(
                modifier = Modifier.fillMaxWidth(),
                icon = HugeIcons.Zap,
                label = stringResource(R.string.stats_page_cached_tokens),
                value = formatTokens(stats.totalCachedTokens),
            )
            // Input tokens already include the cached ones, so the rate is the share of input
            // served from cache. Surfacing it (issue #23) is what lets a user tell an unstable
            // prompt prefix apart from an inherently expensive workload.
            if (stats.totalPromptTokens > 0) {
                val hit = stats.totalCachedTokens.coerceAtMost(stats.totalPromptTokens)
                StatCard(
                    modifier = Modifier.fillMaxWidth(),
                    icon = HugeIcons.Zap,
                    label = stringResource(R.string.stats_page_cache_hit_rate),
                    value = "${hit * 100 / stats.totalPromptTokens}%",
                )
            }
        }
        StatCard(
            modifier = Modifier.fillMaxWidth(),
            icon = HugeIcons.Rocket01,
            label = stringResource(R.string.stats_page_launch_count),
            value = formatCount(stats.launchCount.toLong()),
        )
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    value: String,
) {
    Card(modifier = modifier, colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatCount(count: Long): String = when {
    count >= 1_000_000 -> "%.1fM".format(count / 1_000_000.0)
    count >= 1_000 -> "%.1fK".format(count / 1_000.0)
    else -> count.toString()
}

private fun formatTokens(count: Long): String = when {
    count >= 1_000_000_000 -> "%.2fB".format(count / 1_000_000_000.0)
    count >= 1_000_000 -> "%.2fM".format(count / 1_000_000.0)
    count >= 1_000 -> "%.1fK".format(count / 1_000.0)
    else -> count.toString()
}

// ---- P2-12c: the usage-ledger section ---------------------------------------------------------

/** Rows shown per dimension before the "show all" toggle appears. */
private const val LEDGER_COLLAPSED_ROWS = 5
private const val ORCHESTRATION_COLLAPSED_ROWS = 5

/**
 * P2-12d — the parent→child orchestration tree: the newest sub-agent dispatches, one card per
 * parent conversation.
 *
 * Kept apart from the ledger rankings on purpose: those aggregate every model call in the window by
 * one dimension, while this shows *who dispatched whom*. A run with no ledger rows still appears
 * (with zero calls), because the dispatch itself is the interesting event — the missing rows only
 * say the calls came in before P2-12d started recording a run id.
 */
@Composable
private fun OrchestrationSection(
    trees: List<OrchestrationTree>,
    conversationTitles: Map<String, String>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.stats_page_orchestration_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.stats_page_orchestration_window),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (trees.isEmpty()) {
            Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                Text(
                    text = stringResource(R.string.stats_page_orchestration_empty),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            trees.forEach { tree ->
                OrchestrationTreeCard(
                    tree = tree,
                    parentTitle = tree.parentConversationId?.let { conversationTitles[it] },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun OrchestrationTreeCard(
    tree: OrchestrationTree,
    parentTitle: String?,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val shown = if (expanded) tree.children else tree.children.take(ORCHESTRATION_COLLAPSED_ROWS)

    Card(modifier = modifier, colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = parentTitle
                        ?: stringResource(R.string.stats_page_orchestration_unknown_parent),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.stats_page_orchestration_children, tree.childCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = formatTokens(tree.totalTokens),
                    style = MaterialTheme.typography.labelSmall,
                )
                ledgerCostText(tree.providerCostUsd, tree.costMicros)?.let { cost ->
                    Text(text = cost, style = MaterialTheme.typography.labelSmall)
                }
            }

            shown.forEach { child ->
                OrchestrationChildRow(child)
            }

            if (tree.children.size > ORCHESTRATION_COLLAPSED_ROWS) {
                val toggle = if (expanded) {
                    stringResource(R.string.stats_page_orchestration_collapse)
                } else {
                    stringResource(R.string.stats_page_orchestration_expand, tree.children.size)
                }
                Text(
                    text = toggle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { expanded = !expanded },
                )
            }
        }
    }
}

@Composable
private fun OrchestrationChildRow(node: OrchestrationNode) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    if (node.isRunning) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    }
                ),
        )
        Text(
            text = node.label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = node.status,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = if (node.hasLedgerRows) {
                stringResource(R.string.stats_page_orchestration_calls, node.callCount)
            } else {
                stringResource(R.string.stats_page_orchestration_no_calls)
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = formatTokens(node.totalTokens),
            style = MaterialTheme.typography.labelSmall,
        )
        ledgerCostText(node.providerCostUsd, node.costMicros)?.let { cost ->
            Text(text = cost, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * The four rankings of the ledger window. Kept apart from [StatsGrid] on purpose: that grid reads
 * `message.usage` from the message JSON across all history, while everything here reads
 * `usage_records` and only covers the 90-day retention window, so the two are never summed.
 */
@Composable
private fun LedgerStatsSection(
    view: LedgerStatsView,
    assistantNames: Map<String, String>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.stats_page_ledger_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.stats_page_ledger_window),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (view.total.callCount == 0) {
            Card(colors = CustomColors.cardColorsOnSurfaceContainer) {
                Text(
                    text = stringResource(R.string.stats_page_ledger_empty),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LedgerBucketCard(
                title = stringResource(R.string.stats_page_ledger_by_day),
                buckets = view.byDay,
                view = view,
                labelOf = { it },
                modifier = Modifier.fillMaxWidth(),
            )
            LedgerBucketCard(
                title = stringResource(R.string.stats_page_ledger_by_purpose),
                buckets = view.byPurpose,
                view = view,
                labelOf = { key -> stringResource(purposeLabelRes(key)) },
                modifier = Modifier.fillMaxWidth(),
            )
            LedgerBucketCard(
                title = stringResource(R.string.stats_page_ledger_by_model),
                buckets = view.byModel,
                view = view,
                labelOf = { key ->
                    if (key == UsageStatsFactory.UNKNOWN_KEY) {
                        stringResource(R.string.stats_page_ledger_unknown)
                    } else {
                        key
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            LedgerBucketCard(
                title = stringResource(R.string.stats_page_ledger_by_assistant),
                buckets = view.byAssistant,
                view = view,
                labelOf = { key ->
                    assistantNames[key]
                        ?: if (key == UsageStatsFactory.UNKNOWN_KEY) {
                            stringResource(R.string.stats_page_ledger_unknown)
                        } else {
                            key.take(8)
                        }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun LedgerBucketCard(
    title: String,
    buckets: List<UsageStatBucket>,
    view: LedgerStatsView,
    labelOf: @Composable (String) -> String,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val shown = if (expanded) buckets else buckets.take(LEDGER_COLLAPSED_ROWS)

    Card(modifier = modifier, colors = CustomColors.cardColorsOnSurfaceContainer) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)

            shown.forEach { bucket ->
                LedgerBucketRow(
                    bucket = bucket,
                    share = view.shareOfTokens(bucket),
                    label = labelOf(bucket.key),
                )
            }

            if (buckets.size > LEDGER_COLLAPSED_ROWS) {
                val toggle = if (expanded) {
                    stringResource(R.string.stats_page_ledger_collapse)
                } else {
                    stringResource(R.string.stats_page_ledger_expand, buckets.size)
                }
                Text(
                    text = toggle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { expanded = !expanded },
                )
            }
        }
    }
}

@Composable
private fun LedgerBucketRow(
    bucket: UsageStatBucket,
    share: Float,
    label: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.stats_page_ledger_calls, bucket.callCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatTokens(bucket.totalTokens),
                style = MaterialTheme.typography.labelSmall,
            )
            ledgerCostText(bucket.providerCostUsd, bucket.costMicros)?.let { cost ->
                Text(text = cost, style = MaterialTheme.typography.labelSmall)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(share.coerceIn(0f, 1f))
                    .height(4.dp)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/**
 * Same rule as the message footer (P2-12b): a provider-reported cost is shown bare, a cost
 * recomputed from the local price table gets a `~`, and a call nobody could price shows nothing
 * rather than "$0" - a missing price is not free.
 */
private fun ledgerCostText(providerCostUsd: Double?, costMicros: Long?): String? {
    if (providerCostUsd != null && providerCostUsd > 0.0) return formatCost(providerCostUsd)
    if (costMicros != null && costMicros > 0L) return "~" + formatCost(costMicros / 1_000_000.0)
    return null
}

private fun purposeLabelRes(purpose: String): Int = when (purpose) {
    UsagePurpose.MAIN.name -> R.string.chat_message_usage_purpose_main
    UsagePurpose.TOOL_LOOP.name -> R.string.chat_message_usage_purpose_tool_loop
    UsagePurpose.COMPACTION.name -> R.string.chat_message_usage_purpose_compaction
    UsagePurpose.TITLE.name -> R.string.chat_message_usage_purpose_title
    UsagePurpose.SUGGESTION.name -> R.string.chat_message_usage_purpose_suggestion
    UsagePurpose.MEMORY_EXTRACT.name -> R.string.chat_message_usage_purpose_memory_extract
    UsagePurpose.SUBAGENT.name -> R.string.chat_message_usage_purpose_subagent
    UsagePurpose.CRON.name -> R.string.chat_message_usage_purpose_cron
    UsagePurpose.WORKFLOW.name -> R.string.chat_message_usage_purpose_workflow
    UsagePurpose.SKILL_TEST.name -> R.string.chat_message_usage_purpose_skill_test
    UsagePurpose.TRANSLATION.name -> R.string.chat_message_usage_purpose_translation
    else -> R.string.chat_message_usage_purpose_unknown
}
