package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * T-10 / (9) + T-12 / (11) — which tools a conversation with **no approval channel at all**
 * must not run unattended.
 *
 * `ChatService.isToolAutoApproved` auto-approves *every* tool in a conversation registered via
 * [HeadlessConversations.mark] (cron / sub-agent / workflow / skill-tester /
 * external-automation), on the reasoning that the user pre-authorised the schedule itself at
 * job-creation time and there is no UI to prompt at fire time. The reasoning is sound for the
 * ordinary tool set, and wrong for the four groups below — those are exactly the tools whose
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
     * T-12 / (11) — the rest of the private surface: everything that reads or sends data
     * belonging to the user *personally*, plus the one write that outlives the run.
     *
     * T-10 stopped at `NO_ALWAYS_ALLOW` and the microphone pair. That left the far larger
     * [ToolApprovalDefaults.ALWAYS_ASK] group (132 names) auto-approving in headless runs —
     * deliberate for `read_file` / `web_fetch` / `termux_run_command` (a schedule has to be
     * able to do its job), and wrong for these: a run that reads the SMS inbox, the contact
     * list or the call log, fires the camera, re-sends a notification, or posts a message as
     * the user is a thing nobody asked for and nobody is watching.
     *
     * `memory_write` rides along for a different reason: it is a *persistent* write. An
     * unattended run that appends to its own memory silently changes every later turn — the
     * same "no reviewer present" problem. (T-06's delivery note flagged this one and handed it
     * to the headless-approval card; T-10's chosen scope did not reach it.)
     *
     * Deliberately narrow. `get_location` is NOT here because it does not require approval in
     * the first place (its tool definition carries no `needsApproval`), so it is outside this
     * policy's remit; reading *existing* files is not here either, because a scheduled job
     * needs it to do anything at all.
     */
    val PRIVATE_DATA_TOOL_NAMES: Set<String> = setOf(
        // Contacts
        "list_contacts",
        "search_contacts",
        "create_contact",
        // Messages and calls
        "list_sms_inbox",
        "search_sms",
        "send_sms",
        "send_sms_intent",
        "list_call_log",
        // Camera and screen
        "take_photo",
        "take_screenshot",
        // Notifications
        "notification_action_click",
        "notification_reply",
        "dismiss_notification",
        "send_email_intent",
        // The assistant's own persistent memory
        "memory_write",
    )

    /**
     * P2-06b — the expert-library WRITE tools (`subagent_create` / `subagent_update` /
     * `subagent_delete`). Not a privacy surface: these are here because the roster is what every
     * later dispatch resolves against. An unattended cron job must not be able to hand itself a
     * new expert, quietly edit the one the user tuned, or delete it out from under them — there
     * is nobody to approve the change. In an ordinary conversation each call still prompts (all
     * three are in [ToolApprovalDefaults.ALWAYS_ASK]), so nothing is subtracted from an attended
     * run.
     *
     * Second layer: `SubAgentToolSurface` already denies the `subagent_` prefix to any sub-agent
     * surface, so a sub-agent run — itself headless — never even sees these handles. This entry
     * is what covers cron / workflow / external-automation conversations, which keep their full
     * tool list.
     */
    val EXPERT_WRITE_TOOL_NAMES: Set<String> = setOf(
        "subagent_create",
        "subagent_update",
        "subagent_delete",
    )

    /**
     * Every tool this policy refuses in a headless run: the per-call-confirmation set
     * ([ToolApprovalDefaults.NO_ALWAYS_ALLOW]), [PRIVACY_SENSITIVE_TOOL_NAMES],
     * [PRIVATE_DATA_TOOL_NAMES] and [EXPERT_WRITE_TOOL_NAMES].
     *
     * `NO_ALWAYS_ALLOW` is derived rather than hardcoded, so a tool added to it upstream is
     * covered here without a second edit — the direction that fails safe. The two local sets
     * are spelled out on purpose: they are policy, not a mirror of an upstream constant.
     */
    val REFUSED_TOOL_NAMES: Set<String> =
        ToolApprovalDefaults.NO_ALWAYS_ALLOW + PRIVACY_SENSITIVE_TOOL_NAMES +
            PRIVATE_DATA_TOOL_NAMES + EXPERT_WRITE_TOOL_NAMES

    /**
     * Why [toolName] must not run in a headless conversation, or null when it may.
     *
     * Each group gets its own wording because the operator-facing fix differs: one needs a
     * person to confirm the call, the next needs a person to be *present*, the last needs the
     * user to manage the roster themselves — from the settings screen, or a conversation where
     * they can approve the call.
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
            name in PRIVATE_DATA_TOOL_NAMES ->
                "$name reads or sends the user's own data — contacts, messages, call log, the " +
                    "camera or screen, notifications — or overwrites the assistant's persistent " +
                    "notes. This conversation has no approval channel, so nobody is there to " +
                    "consent. It cannot run unattended."
            name in EXPERT_WRITE_TOOL_NAMES ->
                "$name rewrites the expert library, which is the set of named specialists " +
                    "subagent_dispatch resolves. This conversation has no approval channel, so " +
                    "the user cannot review the change. Ask the user to create or edit experts " +
                    "from the settings screen, or run the dispatch without one."
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
