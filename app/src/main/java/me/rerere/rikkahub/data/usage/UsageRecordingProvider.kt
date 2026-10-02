package me.rerere.rikkahub.data.usage

import android.util.Log
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.UIMessage

private const val TAG = "UsageLedger"

/**
 * P2-11c2 - the single collection point.
 *
 * Wraps whatever provider [me.rerere.ai.provider.ProviderManager] would have returned, so
 * every text generation in the app - chat turn, tool-loop follow-ups, title, suggestion,
 * compaction, translation - is accounted for without changing a single call site.
 *
 * Only generateText is wrapped: it is the method all of those paths ultimately call, and it
 * returns the finished message with the provider-reported usage attached. The wrapper is
 * transparent: the delegate result is returned untouched, and a ledger failure is contained
 * here, because a chat turn must never fail because telemetry did.
 */
internal class UsageRecordingProvider<T : ProviderSetting>(
    private val delegate: Provider<T>,
    private val ledger: UsageLedger,
) : Provider<T> by delegate {

    override suspend fun generateText(
        providerSetting: T,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult {
        val startedAt = System.currentTimeMillis()
        val result = delegate.generateText(providerSetting, messages, params)
        val outcome = UsageCallRecorder.record(
            ledger = ledger,
            usage = result.usage,
            context = currentUsageCallContext() ?: UsageCallContext(),
            providerName = providerSetting.name,
            modelId = params.model.modelId,
            latencyMs = System.currentTimeMillis() - startedAt,
        )
        if (outcome is UsageCallRecorder.Outcome.Failed) {
            Log.w(TAG, "usage ledger write failed for model ${params.model.modelId}", outcome.error)
        }
        return result
    }
}

/**
 * ProviderManager hands out star-projected providers while the decorator is generic over the
 * setting type, so the capture happens here once instead of at every call site. The cast is
 * the same unchecked one ProviderManager itself performs when it maps a setting to a
 * registered provider.
 */
@Suppress("UNCHECKED_CAST")
internal fun decorateForUsage(provider: Provider<*>, ledger: UsageLedger): Provider<*> =
    UsageRecordingProvider(provider as Provider<ProviderSetting>, ledger)
