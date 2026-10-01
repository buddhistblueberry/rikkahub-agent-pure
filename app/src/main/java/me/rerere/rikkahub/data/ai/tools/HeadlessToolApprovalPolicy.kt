package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * T-10 / (9) — which tools a conversation with **no approval channel at all** must not run
 * unattended.
 *
 * `ChatService.isToolAutoApproved` auto-approves *every* tool in a conversation registered via
 * [HeadlessConversations.mark] (cron / sub-agent / workflow / skill-tester /
 * external-automation), on the reasoning that the user pre-authorised the schedule itself at
 * job-creation time and there is no UI to prompt at fire time. The reasoning is sound for the
 * ordinary tool set, and wrong for the two groups below — those are exactly the tools whose
 * *point* is that a human looks at each call.
 *
 * The refusal has to be an **explicit one**. Flipping the tool to `Pending` instead would break
 * the turn: nobody can answer, so `GenerationLoop` would `break` and wait forever. Callers
 * therefore emit [refusalEnvelope] as the tool's output — the same shape the HARDLINE floor
 * uses — so the model sees a structured refusal and can pivot to another approach.
 *
 * Deliberately pure (no Android, no I/O, no globals beyond two constant sets) so the whole
 * policy is unit-testable; the "is this conversation headless?" question stays with the
 * caller, which is where the answer actually lives.
 */
object HeadlessToolApprovalPolicy {

    /**
     * The `error` code emitted to the model. Matches the vocabulary
     * `SubAgentToolSurface.denialReason` (T-09) uses for the same class of rejection, so a
     * tool refused at the surface and a tool refused at execution read the same to a model.
     * Not present in `ToolExecutionRetryPolicy.TRANSIENT_ERROR_CODES` — a refusal is
     * deterministic, so it must never be retried.
     */
    const val ERROR_CODE: String = "tool_not_authorized"

    /**
     * Tools that can technically finish inside a headless run — they touch no device UI and
     * never block — but must not, because finishing means **capturing the user's surroundings
     * or speech with nobody present to consent**.
     *
     *  - `record_audio` → opens the microphone and writes what it hears to a file
     *  - `speech_to_text` → opens the microphone and ships the audio to a recognizer
     *
     * Both live in [ToolApprovalDefaults.ALWAYS_ASK], so an ordinary conversation prompts on
     * every call. A headless one did not — the hole this card closes. This mirrors
     * `SubAgentToolSurface.PRIVACY_SENSITIVE_TOOL_NAMES` (T-09), kept as its own copy rather
     * than a shared reference so the two surfaces can diverge on purpose.
     */
    val PRIVACY_SENSITIVE_TOOL_NAMES: Set<String> = setOf(
        "record_audio",
        "speech_to_text",
    )

    /**
     * Every tool this policy refuses in a headless run: the per-call-confirmation set
     * ([ToolApprovalDefaults.NO_ALWAYS_ALLOW]) plus [PRIVACY_SENSITIVE_TOOL_NAMES].
     *
     * Derived rather than hardcoded, so a tool added to `NO_ALWAYS_ALLOW` upstream is covered
     * here without a second edit — the direction that fails safe.
     */
    val REFUSED_TOOL_NAMES: Set<String> =
        ToolApprovalDefaults.NO_ALWAYS_ALLOW + PRIVACY_SENSITIVE_TOOL_NAMES

    /**
     * Why [toolName] must not run in a headless conversation, or null when it may.
     *
     * The two groups get different wording because the operator-facing fix differs: one needs
     * a person to confirm the call, the other needs a person to be *present*.
     */
    fun refusalDetail(toolName: String): String? {
        val name = toolName.trim()
        return when {
            name.isEmpty() -> null
            name in ToolApprovalDefaults.NO_ALWAYS_ALLOW ->
                "$name requires the user to confirm every single call, and this conversation has " +
                    "no approval channel — there is nobody to confirm. It cannot run unattended. " +
                    "If the task really needs it, ask the user to run it themselves, or use a " +
                    "tool that does not need a per-call confirmation."
            name in PRIVACY_SENSITIVE_TOOL_NAMES ->
                "$name records the user's surroundings or speech and needs somebody present to " +
                    "consent. This conversation has no approval channel, so there is nobody to " +
                    "ask. It cannot run unattended."
            else -> null
        }
    }

    /** Convenience predicate over [refusalDetail]. */
    fun isRefused(toolName: String): Boolean = refusalDetail(toolName) != null

    /**
     * The JSON envelope handed back to the model in place of the tool's real output, or null
     * when [toolName] may run.
     */
    fun refusalEnvelope(toolName: String): String? {
        val detail = refusalDetail(toolName) ?: return null
        return Json.encodeToString(
            buildJsonObject {
                put("error", JsonPrimitive(ERROR_CODE))
                put("tool", JsonPrimitive(toolName.trim()))
                put("detail", JsonPrimitive(detail))
            }
        )
    }

    /**
     * [refusalEnvelope] gated on whether the call is actually coming from a conversation with
     * no approval channel.
     *
     * Keeping [headless] a parameter — instead of having this object read
     * [HeadlessConversations] itself — is what turns "a foreground conversation is never
     * affected" from a claim into a unit-testable property.
     */
    fun refusalEnvelopeFor(toolName: String, headless: Boolean): String? =
        if (headless) refusalEnvelope(toolName) else null
}
