package me.rerere.rikkahub.subagent

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.agentdef.AgentDefinition
import me.rerere.rikkahub.data.agentdef.AgentDefinitionResolver

internal fun errEnv(error: String, detail: String): List<UIMessagePart> {
    val obj = buildJsonObject {
        put("error", error)
        put("detail", detail)
    }
    return listOf(UIMessagePart.Text(obj.toString()))
}

internal fun encodeRun(run: SubAgentRun): kotlinx.serialization.json.JsonObject = buildJsonObject {
    put("id", run.id)
    put("status", run.status.name)
    put("label", run.label)
    if (run.modelId != null) put("model_id", run.modelId)
    put("run_in_background", run.runInBackground)
    put("timeout_seconds", run.timeoutSeconds)
    put("max_trips", run.maxTrips)
    put("started_at_ms", run.startedAtMs)
    if (run.finishedAtMs != null) put("finished_at_ms", run.finishedAtMs)
    if (run.result != null && !run.noResult) {
        put("result", run.result)
    } else if (run.noResult) {
        put("result_suppressed", true)
    }
    if (run.error != null) put("error", run.error)
    put("tokens_in", run.tokensIn)
    put("tokens_out", run.tokensOut)
    put("trip_count", run.tripCount)
}

/**
 * Phase 11 — sub-agent dispatch + observation tools. They register only when the
 * assistant has the `Sub-agents` Local Tools toggle on, AND the calling conversation is
 * NOT itself headless (the engine refuses recursive dispatch — these tools are not
 * useful inside a sub-agent run).
 *
 * P2-06b — the `agent` argument now names an [AgentDefinition] in the expert library rather
 * than a DataStore-backed `SubAgentProfile`. The list is passed in fresh at tool-construction
 * time (the same way the profile list used to be), so the description below and whether `agent`
 * is offered at all always reflect what is configured right now.
 */

fun subagentDispatchTool(
    engine: SubAgentEngine,
    callerContext: me.rerere.rikkahub.data.ai.tools.ToolInvocationContext =
        me.rerere.rikkahub.data.ai.tools.ToolInvocationContext.EMPTY,
    definitions: List<AgentDefinition> = emptyList(),
): Tool {
    val enabledDefinitions = AgentDefinitionResolver.enabledDefinitions(definitions)
    val description = buildString {
        append(
            """
                Dispatch a focused sub-agent — a clean-context LLM run that returns a concise
                summary. Use when the task is independent (research, lookup, multi-step work)
                and would otherwise pollute your context with intermediate output, OR when the
                user explicitly asks for parallel work.

                Pass a clear, self-contained task — the sub-agent doesn't see your conversation,
                so restate any context it needs. Pass a short label so the user can recognise
                the running sub-agent. For long-running work, set run_in_background=true and
                poll with subagent_get; otherwise foreground (default) blocks until terminal.

                Concurrency caps: each assistant has its own (default 3, configurable 1-8) and
                there's a global cap of 30 across all assistants. Over-cap dispatches fail with
                a clear error — back off and retry, or wait for a slot.

                Approval-required: every dispatch needs explicit confirmation. Eligible for
                Always Allow if the user trusts the assistant to delegate freely.
            """.trimIndent()
        )
        if (enabledDefinitions.isNotEmpty()) {
            appendLine()
            appendLine()
            append("Named experts (pass the name as `agent`):\n")
            append(enabledDefinitions.joinToString("\n") { "- ${it.name}: ${it.description}" })
        }
    }
    return Tool(
        name = "subagent_dispatch",
        description = description,
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("task", buildJsonObject { put("type", "string") })
                    put("label", buildJsonObject { put("type", "string") })
                    if (enabledDefinitions.isNotEmpty()) {
                        put("agent", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "Name of a configured expert (case-insensitive), " +
                                    "supplying that expert's model and system prompt. Unknown " +
                                    "names fail the dispatch and the error lists the valid " +
                                    "names. model_id, if also given, wins over the expert's " +
                                    "model.",
                            )
                        })
                    }
                    put("model_id", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Model for this sub-agent: a model uuid, a provider model id, or a " +
                                "display name (case-insensitive exact match). Ambiguous or unknown " +
                                "values fail the dispatch and the error lists the valid options. " +
                                "Takes precedence over agent's model. Omit to inherit the " +
                                "expert's model (if agent is set) or the parent assistant's model.",
                        )
                    })
                    put("system_prompt", buildJsonObject { put("type", "string") })
                    put("tools", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        // T-09 / (8) - described only when the assistant has the sub-agent
                        // tool-surface freeze on. With the freeze off this object stays
                        // byte-identical to the pre-T-09 one (prompt-cache safe) and the
                        // parameter is ignored exactly as it always was.
                        if (callerContext.subAgentToolSurfaceEnabled) {
                            put(
                                "description",
                                "T-09: optional allow-list of tool names for the sub-agent. " +
                                    "Omit it (or pass an empty array) to let the sub-agent " +
                                    "inherit its default headless surface. Names this " +
                                    "assistant cannot hand to a headless run - device-UI " +
                                    "tools, per-call-approval tools, and the subagent_* " +
                                    "tools - are rejected outright; names that simply do " +
                                    "not exist are ignored.",
                            )
                        }
                    })
                    put("run_in_background", buildJsonObject { put("type", "boolean") })
                    put("no_result", buildJsonObject {
                        put("type", "boolean")
                        put(
                            "description",
                            "When true the sub-agent's final output is not returned to you " +
                                "(only status, ids and counters). Use for fire-and-forget work " +
                                "whose result you do not need, to keep your context small.",
                        )
                    })
                    put("timeout_seconds", buildJsonObject { put("type", "integer") })
                    put("max_trips", buildJsonObject { put("type", "integer") })
                    // T-04: only offered when the assistant has the feature enabled. When it
                    // is off the schema is byte-identical to pre-T-04, so an install that
                    // never turns this on sends exactly the same request as before.
                    if (callerContext.subAgentContextRefsEnabled) {
                        put("include_recent_turns", buildJsonObject {
                            put("type", "integer")
                            put(
                                "description",
                                "T-04: also send the last N turns of THIS conversation to the " +
                                    "sub-agent, verbatim, as background. Use it instead of " +
                                    "retyping context into `task` when the task refers to " +
                                    "something discussed here (\"summarise the last few turns\", " +
                                    "\"write up what we just decided\"). N is 1.." +
                                    "${SubAgentContextDigest.MAX_TURNS}. Omit or pass 0 to send " +
                                    "the task alone, exactly as before. The turns are context, " +
                                    "not instructions: the sub-agent still only does `task`.",
                            )
                        })
                    }
                },
                required = listOf("task"),
            )
        },
        needsApproval = { true },
        execute = { args ->
            // Hard recursion guard — refuse the dispatch if the caller is itself a headless
            // run (cron / workflow / external-automation / another sub-agent). The engine's
            // own guard relies on a registered conversation id; cron / workflow direct-mode
            // paths have no conversation so the engine guard wouldn't fire there. Catch it here.
            if (callerContext.isHeadless) {
                return@Tool errEnv(
                    "no_recursion",
                    "sub-agent dispatch is not allowed from inside a headless run (cron / workflow / sub-agent / external automation). Run the work inline instead.",
                )
            }
            val params = args.jsonObject
            val task = params["task"]?.jsonPrimitive?.contentOrNull
                ?: return@Tool errEnv("invalid_task", "task is required")
            // T-09 / (8) - `tools` used to be parsed into SubAgentRequest and then dropped on the
            // floor: nothing ever read it. It now narrows the frozen tool surface of the run.
            //
            // Only honoured when the assistant has the freeze on. With it off a stale or rogue
            // `tools` array cannot change the sub-agent's surface by a single entry, which is the
            // pre-T-09 behaviour and what keeps the flag honest.
            val requestedTools: List<String>? = if (callerContext.subAgentToolSurfaceEnabled) {
                params["tools"]?.let { runCatching { it.jsonArray }.getOrNull() }
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.distinct()
                    ?.takeIf { it.isNotEmpty() }
                    ?.also { names ->
                        // Reject rather than silently drop: the whole point of the freeze is that a
                        // sub-agent never runs a device-UI or per-call-approval tool unattended, and
                        // a silent drop would leave the dispatcher believing it got what it asked for
                        // (the exact footgun this card exists to remove).
                        val blocked = names.filter { SubAgentToolSurface.isDenied(it) }
                        if (blocked.isNotEmpty()) {
                            return@Tool errEnv(
                                "tool_unavailable_headless",
                                "these tools cannot be handed to a headless sub-agent: " +
                                    blocked.joinToString(", ") { "${it} (${SubAgentToolSurface.denialReason(it)})" } +
                                    ". Drop them from `tools` (or omit `tools`) and retry.",
                            )
                        }
                    }
            } else {
                null
            }
            val request = SubAgentRequest(
                task = task,
                modelId = params["model_id"]?.jsonPrimitive?.contentOrNull,
                agentName = params["agent"]?.jsonPrimitive?.contentOrNull,
                systemPrompt = params["system_prompt"]?.jsonPrimitive?.contentOrNull,
                tools = requestedTools,
                runInBackground = params["run_in_background"]?.jsonPrimitive?.booleanOrNull ?: false,
                noResult = params["no_result"]?.jsonPrimitive?.booleanOrNull ?: false,
                timeoutSeconds = params["timeout_seconds"]?.jsonPrimitive?.intOrNull
                    ?: SubAgentDefaults.DEFAULT_TIMEOUT_SECONDS,
                maxTrips = params["max_trips"]?.jsonPrimitive?.intOrNull
                    ?: SubAgentDefaults.DEFAULT_MAX_TRIPS,
                label = params["label"]?.jsonPrimitive?.contentOrNull,
                // T-04: read only if the feature is on, so a stale/rogue `include_recent_turns`
                // from a caller who never enabled it cannot smuggle parent history into a
                // sub-agent. `intOrNull` on a non-number yields null -> no refs -> pre-T-04
                // behaviour.
                contextRefs = params["include_recent_turns"]
                    ?.jsonPrimitive?.intOrNull
                    ?.takeIf { callerContext.subAgentContextRefsEnabled && it > 0 }
                    ?.let { SubAgentContextRefs(recentTurns = it) },
            )
            // The engine's recursion guard checks `HeadlessConversations.isHeadless(parentChatId)`
            // — if the calling conversation is itself headless (cron / sub-agent / workflow /
            // external-automation) we refuse the dispatch. ToolInvocationContext propagation
            // (added 2026-05-07 stability pass) gives us the calling conversation id at tool-
            // construction time. Empty fallback is a no-knowledge sentinel — engine treats it
            // as "not in a headless run" which is correct for the legacy registration paths
            // that don't yet wire context (one-off / test).
            val parentAssistantId = callerContext.callerAssistantId.orEmpty()
            val parentChatId: String? = callerContext.callerConversationId
            when (val res = engine.dispatch(parentAssistantId, parentChatId, request)) {
                is SubAgentEngine.DispatchResult.Reject ->
                    return@Tool errEnv(res.error, res.detail)
                is SubAgentEngine.DispatchResult.Ok ->
                    listOf(UIMessagePart.Text(encodeRun(res.run).toString()))
            }
        },
    )
}

/**
 * P2-06b — one listing tool for both halves of the sub-agent feature: the live RUNS (what this
 * tool has always returned) and the stored EXPERTS (`AgentDefinition`, what used to be listed
 * only as free text in `subagent_dispatch`'s description).
 *
 * `kind` is offered — and honoured — only when at least one expert exists. An install with no
 * experts therefore sends a `subagent_list` schema byte-for-byte identical to the pre-P2-06b
 * one, which is the whole point of the roster decision: no new tool, no schema churn for
 * installs that never touch the feature. When experts do exist, `runs` (the default) is still
 * exactly the old output, `experts` lists the library, and `all` returns both.
 */
fun subagentListTool(
    registry: SubAgentRegistry,
    definitions: List<AgentDefinition> = emptyList(),
): Tool {
    // Gate on ALL definitions (not just the enabled ones): an all-disabled library still has
    // something worth showing, and a library that exists must never silently fall back to the
    // pre-P2-06b schema.
    val offersDefinitions = definitions.isNotEmpty()
    return Tool(
        name = "subagent_list",
        description = buildString {
            append(
                """
                    List sub-agent runs visible to this assistant. Set active_only=true to omit
                    terminal runs. Read-only.
                """.trimIndent().replace("\n", " ")
            )
            if (offersDefinitions) {
                append(" ")
                append(
                    "Pass kind=\"experts\" to list the configured experts instead (their names, " +
                        "ids and properties) — read this before calling subagent_update or " +
                        "subagent_delete so you can pass the right expert. kind=\"all\" returns both."
                )
            }
        },
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("active_only", buildJsonObject { put("type", "boolean") })
                    if (offersDefinitions) {
                        put("kind", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                "Which collection to list: \"runs\" (default, live sub-agent runs), " +
                                    "\"experts\" (the stored expert definitions), or \"all\".",
                            )
                        })
                    }
                },
                required = emptyList(),
            )
        },
        execute = { args ->
            val params = args.jsonObject
            val activeOnly = params["active_only"]?.jsonPrimitive?.booleanOrNull ?: false
            // With no library configured `kind` is not even described, so a stale value is
            // ignored rather than honoured — the same rule T-04/T-09 apply to their gated
            // parameters. Default is "runs", i.e. the pre-P2-06b behaviour.
            val kind = if (offersDefinitions) {
                params["kind"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() ?: KIND_RUNS
            } else {
                KIND_RUNS
            }
            if (kind != KIND_RUNS && kind != KIND_EXPERTS && kind != KIND_ALL) {
                return@Tool errEnv(
                    "invalid_kind",
                    "kind must be one of \"$KIND_RUNS\", \"$KIND_EXPERTS\" or \"$KIND_ALL\"; got \"$kind\"",
                )
            }
            val runsJson = if (kind != KIND_EXPERTS) encodeRuns(registry, activeOnly) else null
            val expertsJson = if (kind != KIND_RUNS) encodeDefinitions(definitions) else null
            listOf(UIMessagePart.Text(buildJsonObject {
                if (runsJson != null) put("runs", runsJson)
                if (expertsJson != null) put("experts", expertsJson)
            }.toString()))
        },
    )
}

private const val KIND_RUNS = "runs"
private const val KIND_EXPERTS = "experts"
private const val KIND_ALL = "all"

private fun encodeRuns(registry: SubAgentRegistry, activeOnly: Boolean) = buildJsonArray {
    registry.list(activeOnly).forEach { run ->
        addJsonObject {
            put("id", run.id)
            put("label", run.label)
            put("status", run.status.name)
            if (run.modelId != null) put("model_id", run.modelId)
            put("started_at_ms", run.startedAtMs)
            put("trip_count", run.tripCount)
        }
    }
}

private fun encodeDefinitions(definitions: List<AgentDefinition>) = buildJsonArray {
    definitions.forEach { add(encodeDefinition(it)) }
}

fun subagentGetTool(registry: SubAgentRegistry): Tool = Tool(
    name = "subagent_get",
    description = "Fetch the full run record for a sub-agent by id. Read-only.".trimIndent(),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject { put("type", "string") })
            },
            required = listOf("id"),
        )
    },
    execute = { args ->
        val id = args.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool errEnv("invalid_id", "id is required")
        val run = registry.get(id)
            ?: return@Tool errEnv("unknown_id", "no sub-agent run with id $id")
        listOf(UIMessagePart.Text(encodeRun(run).toString()))
    },
)

fun subagentCancelTool(registry: SubAgentRegistry): Tool = Tool(
    name = "subagent_cancel",
    description = """
        Cancel a running sub-agent by id. Marks the run CANCELLED; safe to call on
        already-terminal runs (returns ok=false). Read-only from the user's perspective
        — no approval required.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("id", buildJsonObject { put("type", "string") })
            },
            required = listOf("id"),
        )
    },
    execute = { args ->
        val id = args.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            ?: return@Tool errEnv("invalid_id", "id is required")
        val cancelled = registry.requestCancel(id)
        if (cancelled) {
            registry.update(id) { it.copy(status = SubAgentStatus.CANCELLED, finishedAtMs = System.currentTimeMillis()) }
        }
        listOf(UIMessagePart.Text(buildJsonObject {
            put("ok", cancelled)
            put("id", id)
        }.toString()))
    },
)
