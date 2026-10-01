package me.rerere.rikkahub.subagent

import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

/**
 * T-09 / (8) — freeze the tool surface of a sub-agent run.
 *
 * ## Why this exists
 *
 * A sub-agent is executed as a headless
 * [me.rerere.rikkahub.data.ai.tools.HeadlessConversations] run whose conversation uses the
 * SAME assistant as its parent (`SubAgentEngine.executeRun`: `Conversation.ofId(assistantId =
 * parentAsstUuid)`). Two things follow from that, and both are bad:
 *
 *  1. **The child inherits the parent's complete tool surface** — local tools, browser,
 *     external storage, NFC, workspace, skills, MCP, and the `subagent_*` handles themselves.
 *     The `subagent_*` tools are harmless only because three `isHeadless` checks refuse to act
 *     (the engine's recursion guard); they still cost tokens on every single request and invite
 *     the model to waste a whole tool trip discovering that dispatch is refused.
 *  2. **Headless conversations auto-approve every tool.** `ChatService`'s
 *     `isToolAutoApproved` returns true for any conversation registered with
 *     `HeadlessConversations.mark()`, and that check does NOT consult
 *     [ToolApprovalDefaults.NO_ALWAYS_ALLOW]. So a sub-agent can call `eval_javascript`,
 *     `mcp_add`, `skill_install_from_url`, `keystore_generate_key`, `keystore_decrypt`,
 *     `grant_directory_access` or `nfc_write_tag` **unattended** — exactly the "confirm every
 *     single call, never blanket-allow" guarantee those tools are documented to have. That is a
 *     privilege-escalation hole, not a token-count nit: the parent may be prompt-injected, and
 *     the injected instruction now runs in a context that cannot ask the user anything.
 *
 * ## What it does
 *
 * [freeze] records, for one conversation id, the surface that sub-agent is allowed to see.
 * [apply] is then called by every `ChatService` tool-assembly site right after the final
 * `List<Tool>` is built (workspace / skill / MCP tools are appended *outside*
 * `LocalTools.getTools`, so the filter has to run on the finished list, not on the options).
 *
 * The policy is a **deny list plus an optional narrowing allow-list**:
 *
 *  - [isDenied] removes the tools a headless run must never receive — see
 *    [UI_BOUND_TOOL_NAMES], [ToolApprovalDefaults.NO_ALWAYS_ALLOW] and the `subagent_` prefix.
 *  - the optional `requested` set is whatever the dispatcher passed in `subagent_dispatch`'s
 *    `tools` parameter. That parameter was parsed and then silently dropped before T-09
 *    (nothing ever read `SubAgentRequest.tools`); it now narrows the frozen surface. An absent
 *    or empty array means "no narrowing".
 *
 * Deliberately NOT done here: intersecting with the caller's exact tool-name list. The parent's
 * names are not reachable from the engine (the options → names mapping lives inside
 * `LocalTools`, and `LocalTools` already depends on this engine, so the engine cannot call it),
 * and the child is assembled from the same assistant anyway — so the deny list is what actually
 * changes anything. Filtering the *finished* child list is strictly stronger than the originally
 * planned "expand the parent's options" (it also covers workspace / skill / MCP tools, which are
 * added outside the options list).
 *
 * ## Off by default, byte-identical when off
 *
 * Nothing writes a freeze record unless the assistant has
 * `enableSubAgentToolSurface` on. [apply] returns the **same `List<Tool>` instance** when there is
 * no record, so a flagged-off install sends exactly the tool schemas it sent before T-09.
 */
object SubAgentToolSurface {

    /**
     * Tools that need a human in front of the device and therefore can never complete inside a
     * headless run. Grounded in code, not in guesswork — each one is a `ToolHostActivity` host:
     *
     *  - `take_photo` → `MODE_CAMERA`
     *  - `verify_fingerprint` → `MODE_BIOMETRIC`
     *  - `nfc_read_tag` / `nfc_write_tag` → `MODE_NFC_READ` / `MODE_NFC_WRITE`
     *  - `grant_directory_access` → `MODE_SAF_PICKER`
     *
     * `nfc_write_tag` and `grant_directory_access` are already in
     * [ToolApprovalDefaults.NO_ALWAYS_ALLOW]; they are listed again so the reason a caller reads
     * ("needs a device UI") stays correct even if that set is ever reshuffled.
     *
     * Kept short on purpose. Tools that merely *put something on screen* without blocking
     * (`share`, `open_file`, `launch_app`, `show_toast`, `post_notification`) still work headless,
     * so v1 leaves them alone — a wrong entry here silently breaks a working sub-agent, which is
     * worse than the annoyance it would avoid.
     */
    val UI_BOUND_TOOL_NAMES: Set<String> = setOf(
        "take_photo",
        "verify_fingerprint",
        "nfc_read_tag",
        "nfc_write_tag",
        "grant_directory_access",
    )

    /**
     * Tools whose whole definition is "the parent agent controls itself": dispatch, list, get,
     * cancel. Inside a sub-agent they only ever answer "no_recursion" (three `isHeadless` guards
     * refuse them), so they are pure token cost plus a wasted tool trip. Excluded by PREFIX, so a
     * future `subagent_*` handle is covered without touching this file.
     */
    const val INTERNAL_TOOL_PREFIX: String = "subagent_"

    /**
     * `ask_user` is not a permission gate — it is a request for human input, and a headless run
     * has nobody to answer, so it degrades to an `ask_user_unavailable` envelope. It is excluded
     * to save the trip, not to fix a hang. (Verified 2026-10-01: `LocalTools` returns the
     * envelope rather than blocking; the older "it blocks in headless" theory was wrong.)
     */
    const val ASK_USER_TOOL_NAME: String = "ask_user"

    /**
     * Why a tool may not be handed to a sub-agent, or null when it is fine. The strings match the
     * vocabulary AAAelina's `SubAgentRun` uses for the same rejections, so an error surfaced to a
     * model looks the same in both codebases.
     */
    fun denialReason(toolName: String): String? = when {
        toolName.startsWith(INTERNAL_TOOL_PREFIX) -> "tool_unavailable_headless"
        toolName == ASK_USER_TOOL_NAME -> "tool_unavailable_headless"
        toolName in UI_BOUND_TOOL_NAMES -> "tool_unavailable_headless"
        toolName in ToolApprovalDefaults.NO_ALWAYS_ALLOW -> "tool_not_authorized"
        else -> null
    }

    /** True when [toolName] must never reach a sub-agent's surface. */
    fun isDenied(toolName: String): Boolean = denialReason(toolName) != null

    /**
     * The headless-safe subset of [callerToolNames] — the pure policy function, used to validate a
     * dispatcher-supplied `tools` allow-list and to keep the filter testable without a `Tool`.
     */
    fun safeNames(callerToolNames: Collection<String>): Set<String> =
        callerToolNames.filterNot { isDenied(it) }.toSet()

    /**
     * The frozen surface of one sub-agent conversation.
     *
     * @param requested the names the dispatcher asked for, or null when it asked for no
     * narrowing. Never empty: an empty array is treated as "not specified" so a model that sends
     * `"tools": []` by accident still gets a working sub-agent instead of a tool-less one.
     */
    data class FrozenSurface(val requested: Set<String>?)

    private val frozen = ConcurrentHashMap<Uuid, FrozenSurface>()

    /**
     * Freeze [conversationId] to the headless-safe surface (optionally narrowed to [requested]).
     * Called by the engine right after the sub-agent conversation exists, and released in the same
     * `finally` that unmarks it as headless.
     */
    fun freeze(conversationId: Uuid, requested: Collection<String>? = null): FrozenSurface {
        val surface = FrozenSurface(requested = requested?.filterNot { it.isBlank() }?.toSet()?.takeIf { it.isNotEmpty() })
        frozen[conversationId] = surface
        return surface
    }

    /** Drop the record. After this [apply] is an identity again for this conversation. */
    fun release(conversationId: Uuid) {
        frozen.remove(conversationId)
    }

    /** The record for [conversationId], or null when the conversation was never frozen. */
    fun frozenFor(conversationId: Uuid): FrozenSurface? = frozen[conversationId]

    /**
     * Filter [tools] down to the frozen surface of [conversationId].
     *
     * **Identity contract:** with no freeze record — i.e. every conversation in an install that
     * never turns the feature on, and every non-sub-agent conversation in one that does — the
     * same `List<Tool>` instance is returned, so the schemas handed to the model are
     * byte-for-byte what they were.
     */
    fun apply(conversationId: Uuid, tools: List<Tool>): List<Tool> {
        val surface = frozenFor(conversationId) ?: return tools
        val requested = surface.requested
        val kept = tools.filter { tool ->
            !isDenied(tool.name) && (requested == null || tool.name in requested)
        }
        // Nothing to remove -> hand back the original instance so identity stays observable
        // instead of silently producing an equal-but-new list.
        return if (kept.size == tools.size) tools else kept
    }

    /** Test hook: drop every record. Never called from production code. */
    fun clearAll() {
        frozen.clear()
    }
}
