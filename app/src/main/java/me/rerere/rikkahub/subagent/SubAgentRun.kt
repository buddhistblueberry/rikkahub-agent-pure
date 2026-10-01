package me.rerere.rikkahub.subagent

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/**
 * Phase 11 — sub-agent run record. Lives in [SubAgentRegistry]'s in-memory map for the
 * lifetime of the app process. Persistence intentionally out of scope for v1: spec says
 * "Background sub-agents survive only as long as the parent process is alive" and
 * documents that user-visibly. WorkManager-backed persistence is a v2 concern.
 *
 * The run is FROZEN once it reaches a terminal status. Mutations are done by replacing
 * the entry in the registry's StateFlow rather than mutating in place.
 */
@Serializable
data class SubAgentRun(
    val id: String,
    val parentChatId: String?,         // the parent assistant chat that dispatched this — used for /stop cascade
    val parentAssistantId: String,
    val label: String,
    val task: String,
    val modelId: String?,              // null = inherited from parent
    val tools: List<String>?,          // null = inherited from parent
    val runInBackground: Boolean,
    val noResult: Boolean = false,   // #78/#79: suppress result text from encodeRun/parent notification
    val timeoutSeconds: Int,
    val maxTrips: Int,
    val status: SubAgentStatus,
    val result: String? = null,
    val error: String? = null,
    val startedAtMs: Long,
    val finishedAtMs: Long? = null,
    val tokensIn: Long = 0,
    val tokensOut: Long = 0,
    val tripCount: Int = 0,
)

@Serializable
enum class SubAgentStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED,
}

object SubAgentDefaults {
    const val DEFAULT_TIMEOUT_SECONDS = 300
    const val MAX_TIMEOUT_SECONDS = 1800
    const val DEFAULT_MAX_TRIPS = 12
    const val MAX_MAX_TRIPS = 30
    const val MAX_LABEL_LENGTH = 60
    const val GLOBAL_CONCURRENCY_CAP = 30
    const val MIN_PER_ASSISTANT_CAP = 1
    const val MAX_PER_ASSISTANT_CAP = 8
    const val REGISTRY_LRU_CAP = 50

    /** Default system prompt used when the assistant's per-sub-agent prompt is empty. */
    val DEFAULT_SYSTEM_PROMPT = """
        You are a focused sub-agent dispatched by a parent assistant to complete a single
        task and return a concise summary.

        Rules:
        - Stay tightly scoped to the task you were given. Do not expand scope.
        - Use tools to gather facts before answering when accuracy matters.
        - Return a clear, structured final summary as your last message — that summary is
          what the parent will see. Aim for 100-500 words unless the task asks otherwise.
        - If the task is impossible, return a single short paragraph explaining why.
        - Do not ask the parent for clarification — make the best judgment call you can
          and proceed.
    """.trimIndent()
}

@Serializable
data class SubAgentRequest(
    val task: String,
    val modelId: String? = null,
    /**
     * #36: name of a configured [SubAgentProfile], resolved case-insensitively by
     * [SubAgentProfileResolver]. `modelId` above wins over the profile's model when both are
     * given; see [SubAgentEngine.executeRun].
     */
    val agentName: String? = null,
    val systemPrompt: String? = null,
    val tools: List<String>? = null,
    val runInBackground: Boolean = false,
    val noResult: Boolean = false,
    val timeoutSeconds: Int = SubAgentDefaults.DEFAULT_TIMEOUT_SECONDS,
    val maxTrips: Int = SubAgentDefaults.DEFAULT_MAX_TRIPS,
    val label: String? = null,
    /**
     * T-04 / (4): references to the CALLER's context. A sub-agent runs in a clean context,
     * so the referenced history is materialised as text into its first user message - the
     * referenced messages are never replayed as real turns, and the sub-agent gains no
     * ability to read the parent conversation itself.
     *
     * Null (the default) means "behave exactly as before T-04": the task text alone.
     * Appended last on purpose, same rule as the Assistant model - every existing caller
     * uses named arguments, and a new field at the end cannot re-bind an older positional
     * one.
     */
    val contextRefs: SubAgentContextRefs? = null,
)

/**
 * T-04 / (4) - which slice of the caller's conversation to hand to the sub-agent.
 *
 * `recentTurns` is the whole of v1. Message IDS were deliberately left out: the
 * dispatching model has no way to learn message ids (they are not in the transcript, the
 * tool schema, or any listing tool), so a `message_ids` parameter would be a parameter no
 * caller could ever fill in correctly - a footgun, not a feature. If a future caller does
 * have real ids (a workflow step, an external automation) the engine can grow an overload;
 * the model-facing surface stays turn-based.
 */
@Serializable
data class SubAgentContextRefs(
    /** How many of the newest caller turns to carry. 0 (or an absent [SubAgentRequest.contextRefs]) = none. */
    val recentTurns: Int = 0,
)

object SubAgentRequestValidator {

    sealed class Result {
        data class Ok(val request: SubAgentRequest) : Result()
        data class Reject(val error: String, val detail: String) : Result()
    }

    fun validate(request: SubAgentRequest): Result {
        val task = request.task.trim()
        if (task.isEmpty()) {
            return Result.Reject("invalid_task", "task is required and may not be blank")
        }
        if (request.timeoutSeconds < 1) {
            return Result.Reject(
                "invalid_timeout",
                "timeout_seconds must be at least 1; got ${request.timeoutSeconds}"
            )
        }
        if (request.timeoutSeconds > SubAgentDefaults.MAX_TIMEOUT_SECONDS) {
            return Result.Reject(
                "invalid_timeout",
                "timeout_seconds exceeds max ${SubAgentDefaults.MAX_TIMEOUT_SECONDS}; got ${request.timeoutSeconds}"
            )
        }
        if (request.maxTrips < 1) {
            return Result.Reject(
                "invalid_max_trips",
                "max_trips must be at least 1; got ${request.maxTrips}"
            )
        }
        if (request.maxTrips > SubAgentDefaults.MAX_MAX_TRIPS) {
            return Result.Reject(
                "invalid_max_trips",
                "max_trips exceeds max ${SubAgentDefaults.MAX_MAX_TRIPS}; got ${request.maxTrips}"
            )
        }
        request.label?.let {
            if (it.length > SubAgentDefaults.MAX_LABEL_LENGTH) {
                return Result.Reject(
                    "invalid_label",
                    "label exceeds ${SubAgentDefaults.MAX_LABEL_LENGTH} chars; got ${it.length}"
                )
            }
        }
        // T-04: reject rather than clamp, so a caller that asked for 50 turns learns the
        // real ceiling instead of silently getting 10 and assuming it worked.
        request.contextRefs?.recentTurns?.let { turns ->
            if (turns < 0 || turns > SubAgentContextDigest.MAX_TURNS) {
                return Result.Reject(
                    "invalid_context_refs",
                    "context_refs.recent_turns must be between 0 and " +
                        "${SubAgentContextDigest.MAX_TURNS}; got $turns"
                )
            }
        }
        return Result.Ok(request.copy(task = task))
    }
}

/**
 * #36: a named, reusable sub-agent configuration - a name, description, custom system
 * prompt and model, defined once in settings so the dispatching model can pick a specialist by
 * NAME instead of memorizing a model uuid. Resolved by [SubAgentProfileResolver]. `modelId` null
 * means the profile itself defers to the parent's model, mirroring the "null = inherit"
 * convention already used by [SubAgentRequest.modelId].
 */
@Serializable
data class SubAgentProfile(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val description: String = "",
    val systemPrompt: String = "",
    val modelId: Uuid? = null,
    val enabled: Boolean = true,
)
