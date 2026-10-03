package me.rerere.rikkahub.ui.pages.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.dao.getMessageCountPerDay
import me.rerere.rikkahub.data.db.dao.getTokenStats
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.usage.LedgerStatsView
import me.rerere.rikkahub.data.usage.UsageLedger
import me.rerere.rikkahub.data.usage.UsageLedgerDefaults
import me.rerere.rikkahub.data.usage.UsageStatsFactory
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

data class AppStats(
    val isLoading: Boolean = true,
    val totalConversations: Int = 0,
    val totalMessages: Int = 0,
    val totalPromptTokens: Long = 0L,
    val totalCompletionTokens: Long = 0L,
    val totalCachedTokens: Long = 0L,
    val conversationsPerDay: Map<LocalDate, Int> = emptyMap(),
    val launchCount: Int = 0,
)

class StatsVM(
    private val conversationDAO: ConversationDAO,
    private val messageNodeDAO: MessageNodeDAO,
    private val settingsStore: SettingsStore,
    private val usageLedger: UsageLedger,
) : ViewModel() {

    private val _stats = MutableStateFlow(AppStats())
    val stats = _stats.asStateFlow()

    /**
     * P2-12c - the ledger window, or null before it loads (and if the read fails). Keeping it a
     * separate flow from [stats] is deliberate: [AppStats] counts the whole message history, the
     * ledger only covers the 90-day retention window, so the two must never be added together.
     */
    private val _ledgerStats = MutableStateFlow<LedgerStatsView?>(null)
    val ledgerStats = _ledgerStats.asStateFlow()

    /** assistant id -> display name, so the "by assistant" ranking shows names, not UUIDs. */
    private val _assistantNames = MutableStateFlow<Map<String, String>>(emptyMap())
    val assistantNames = _assistantNames.asStateFlow()

    init {
        viewModelScope.launch { loadStats() }
    }

    private suspend fun loadStats() {
        delay(50)

        val today = LocalDate.now()

        // 热力图起始日期（52 周前的周日），格式 "yyyy-MM-dd" 直接与 JSON 中的 LocalDateTime 前缀比较
        val startDate = today
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
            .minusWeeks(52)
            .toString()

        // 基于用户消息的 createdAt 统计每日活跃消息数，SQLite 侧 GROUP BY，返回 ≤371 行
        val conversationsPerDay = withContext(Dispatchers.IO) {
            messageNodeDAO
                .getMessageCountPerDay(startDate)
                .mapNotNull { entry ->
                    runCatching { LocalDate.parse(entry.day) to entry.count }.getOrNull()
                }
                .toMap()
        }

        val totalConversations = conversationDAO.countAll()

        // json_each() + json_extract() 在 SQLite 侧聚合，不再加载完整 JSON 到 Kotlin
        val tokenStats = messageNodeDAO.getTokenStats()

        val launchCount = settingsStore.settingsFlow.value.launchCount

        _stats.value = AppStats(
            isLoading = false,
            totalConversations = totalConversations,
            totalMessages = tokenStats.totalMessages,
            totalPromptTokens = tokenStats.promptTokens,
            totalCompletionTokens = tokenStats.completionTokens,
            totalCachedTokens = tokenStats.cachedTokens,
            conversationsPerDay = conversationsPerDay,
            launchCount = launchCount,
        )

        loadLedger()
    }

    /**
     * P2-12c - read the ledger's last 90 days and bucket it along the four axes the page shows.
     *
     * Best effort on purpose: the older cards must keep working even if the ledger read fails, so a
     * failure leaves [ledgerStats] null and the section simply does not render.
     *
     * The ledger lives in its own database file, so it cannot be joined to `conversationentity` in
     * SQL. The auxiliary calls (TITLE / SUGGESTION / COMPACTION) run under a conversation but do not
     * set an assistant id on their row, so the conversation -> assistant map is built here and passed
     * in as the fallback resolver.
     */
    private suspend fun loadLedger() {
        _assistantNames.value = settingsStore.settingsFlow.value.assistants
            .associate { it.id.toString() to it.name }

        runCatching {
            val sinceMs = System.currentTimeMillis() -
                UsageLedgerDefaults.RETENTION_DAYS * 24L * 60L * 60L * 1000L
            val rows = usageLedger.recordsSince(sinceMs)
            val conversationToAssistant = withContext(Dispatchers.IO) {
                conversationDAO.getAll().first().associate { it.id to it.assistantId }
            }
            UsageStatsFactory.build(
                records = rows,
                assistantOfConversation = conversationToAssistant::get,
            )
        }.onSuccess { _ledgerStats.value = it }
    }
}
