package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.Tool

/**
 * P2-02 — the per-tool opt-out.
 *
 * `LocalToolOption` is a coarse switch: it turns a whole group on or off (e.g. `sensors` is
 * `list_sensors` **and** `read_sensor`; `media_player` is six tools). This filter adds a
 * second, finer layer driven by
 * [me.rerere.rikkahub.data.model.Assistant.disabledLocalTools] — a set of exact tool names
 * that are removed from the assembled surface even while their group is still enabled.
 *
 * It is applied at the single assembly point (`LocalTools.getTools`), so every caller that
 * goes through `ToolSurfaceResolver` — chat, rerun, fast-path, cron, workflow, sub-agents —
 * sees the same filtered surface.
 *
 * ## Contract
 *
 *  - Empty set ⇒ **nothing happens at all** (no list mutation): an assistant that predates
 *    this card gets the exact same surface it always did.
 *  - The removal is **in place** on purpose: `workflow_create` is built earlier in the same
 *    method with a `knownToolNamesProvider = { tools.map { it.name } }` closure over this very
 *    list, and it must observe the filtered set too.
 *
 * Pure (no Android, no Context) so it is unit-testable on a bare JVM —
 * see `LocalToolsFilterTest`.
 */
object LocalToolFilter {

    /**
     * Removes every tool whose [Tool.name] is in [disabledToolNames] from [tools], in place,
     * preserving the order of the survivors. Returns the same list instance for convenience.
     */
    fun removeDisabled(
        tools: MutableList<Tool>,
        disabledToolNames: Set<String>,
    ): MutableList<Tool> {
        if (disabledToolNames.isNotEmpty()) {
            tools.removeAll { it.name in disabledToolNames }
        }
        return tools
    }
}
