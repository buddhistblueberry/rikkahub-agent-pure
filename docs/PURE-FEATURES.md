# What the Pure fork adds

> Reference page. The [README](../README.md) is the tour; this is the detail — what each
> addition does, **the exact tool and switch names**, and where to find it in the UI.
> Line-level history and per-change evidence live in [`docs/engineering/`](engineering/).

The fork keeps the entire upstream feature set and adds a layer on top. Everything here is
**off by default**; with a switch off, the default path is unchanged.

## At a glance

| # | Addition | What it gives you |
|---|---|---|
| 1 | **Usage ledger & budgets** | Every model call metered by purpose and cost; orchestrations can be capped. |
| 2 | **On-demand tool exposure** | Search a tool directory and load only the schemas the model opens. |
| 3 | **Long-run survival** | Tool-result budgets, model-initiated compaction, execution retry, keep-alive. |
| 4 | **Expert library** | Reusable sub-agents with their own model, tools, namespace and memory. |
| 5 | **Cold memory** | A Markdown knowledge base read on demand instead of pinned in context. |
| 6 | **Workflows that chain** | Data flow between actions plus an encrypted secret store. |
| 7 | **Headless safety fixes** | Closed the paths where background runs auto-approved everything. |

---

## 1. Usage ledger and budgets

**Why.** Upstream counted tokens but never attributed them: you could not tell whether the
spend came from the main turn, a tool loop, a compaction, or a fleet of sub-agents — and you
could not cap it.

**What it does**

- A **separate Room database, `usage_ledger.db`**, kept out of the chat DB. It stores
  **metadata only** — tokens, cost and ids. **Never message content.**
- **Per-call attribution by purpose.** Twelve buckets: `MAIN`, `TOOL_LOOP`, `COMPACTION`,
  `TITLE`, `SUGGESTION`, `MEMORY_EXTRACT`, `SUBAGENT`, `CRON`, `WORKFLOW`, `SKILL_TEST`,
  `TRANSLATION`, `UNKNOWN`.
- **Honest accounting (unknown ≠ zero).** A field the provider did not report stays `null`
  and is never counted as `0`. Cache-hit rate is not polluted by "not reported".
- **Cost is frozen at write time.** Each row stores the price version it was priced with
  (`priceVersionId`) and the computed `costMicro`, with a *recalculate at current prices*
  action. Prices come from a **per-model price table** that understands **peak / off-peak**
  windows.
- **Statistics page** — the ledger grouped by **day / purpose / model / assistant** over the
  last 90 days, with **tok/s** per bucket, plus an **orchestration tree** (parent → child,
  last 50 runs).
- **Budgets that are enforced** — a per-orchestration token ceiling covers the parent turn
  *and* every descendant it fans out. It is checked **before** dispatch; exceeding it returns
  a **structured, model-readable envelope** rather than failing silently. An expert can carry
  its own ceiling, and concurrency is capped.

**Where it lives**

| Thing | Path / name |
|---|---|
| Ledger database | `usage_ledger.db` (separate from the chat DB) |
| Export tool | `usage_export` → CSV / JSON into `/workspace/exports/` (delta via `since`) |
| Price table tools | `usage_get_prices`, `usage_set_prices` (approval-gated, audited) |
| Price table UI | Settings → **Providers** → *model* → **Price** tab |
| Export tool group | Assistant → **Local Tools** → **Usage ledger** |
| Statistics + orchestration tree | Chat **drawer** → **Statistics** |
| Orchestration budget / concurrency | Assistant → **Basic Settings** |
| Per-expert budget override | Settings → **Sub-agent profiles** → *expert* |

---

## 2. On-demand tool exposure

**Why.** Every enabled tool's schema used to be injected on every turn, and tool control was
only per *group* — you could not turn off one tool inside a group you wanted.

**What it does**

- **Tool palette** — search the local tool directory and see which group a tool belongs to
  before enabling it. **56 tool groups, ~180 individual tools.**
- **Per-tool switches** — `Assistant.disabledLocalTools` narrows the granularity from a
  *group* to an *individual tool*.
- **Progressive exposure** — an opt-in tool-surface mode replaces the MCP tools in each
  request with a `tool_search` / `tool_open` pair, so only the schemas the model actually
  opens are sent. Disclosed trade-off: a changing tool list can cost prompt-cache hits.
- **One assembly point** — the six scattered places that used to assemble the tool surface
  were collapsed into one, so every knob composes predictably.

**Where it lives**

| Thing | Path / name |
|---|---|
| Tool palette | Settings → **Sub-agent profiles** → **Tool palette** |
| Per-tool switches | Assistant → **Local Tools** → expand a group |
| Tool-surface mode | Assistant → **Basic Settings** → **Advanced / experimental** → *Tool surface mode* |
| Tools | `tool_search`, `tool_open` |

---

## 3. Long-run survival

**Why.** Long runs failed in four ways: one big tool result filled the context, the model
could not compress on its own, a failed tool call was not retried, and a backgrounded app
killed the run.

**What it does**

- **Tool-result budget** — cap how much context a single tool result may occupy; longer
  results keep their **head and tail**, and the full text is spilled to `/tool_outputs/` for
  the model to read back on demand. Default: the 32 KB spill gate.
- **Model-initiated compaction** — an opt-in `compact_context` tool lets the model summarise
  earlier turns itself, running the same summariser as the manual compress action.
- **Tool-execution retry** — retries at the *execution* layer (not just the stream layer),
  behind an idempotency whitelist.
- **Orchestration keep-alive** — a sub-agent run holds a **foreground service** for its whole
  lifetime, so a long fan-out is not killed when the app is backgrounded.

**Where it lives**

| Thing | Path / name |
|---|---|
| Tool-result budget | Assistant → **Basic Settings** → *Tool result budget* |
| Model-initiated compaction | Assistant → **Basic Settings** → **Advanced / experimental** → *Model-initiated context compaction* (`compact_context`) |
| Execution retry | Built in (idempotency whitelist in `ToolExecutionRetryPolicy`) |
| Keep-alive | Automatic during a dispatch tree |

---

## 4. Sub-agents and the expert library

**Why.** Upstream sub-agents got only a bare `task` string, and "sub-agent profiles" were a
thin DataStore list with no tool surface, no namespace and no memory of their own.

**What it does**

- **Context hand-off** — `subagent_dispatch` can pass recent turns, or selected media, to a
  child, so a task referring to what you were just discussing does not have to be retyped.
  The hand-off travels as plain text; the child still cannot read the parent conversation.
- **Expert library** — persistent, reusable definitions (`AgentDefinition`, Room-backed).
  Each expert carries its own system prompt, model, tool surface, MCP servers, skills, and
  **its own namespace** under `agents/<namespace>/` in the workspace — including a private
  cold-memory folder. A surface group left on *inherit* uses whatever the dispatching
  assistant has.
- **Tool-surface freezing** — a sub-agent only ever sees the **headless-safe** part of the
  assistant's tools: device-UI tools and must-confirm tools are removed, and the dispatch
  call can narrow it further.
- **Three execution strengths** — all-in-one, hybrid, and one-parent-many-children — built
  from **orthogonal knobs**, not a mutually-exclusive mode enum.
- **Audio / video input** — audio and video parts travel through the attachment picker, the
  Chat Completions transport, and into sub-agents.

**Where it lives**

| Thing | Path / name |
|---|---|
| Tools | `subagent_dispatch`, `subagent_get`, `subagent_list`, `subagent_cancel`, `subagent_tool`, `subagent_create`, `subagent_update`, `subagent_delete` |
| Expert editor | Settings → **Sub-agent profiles** |
| Context refs switch | Assistant → **Basic Settings** → **Advanced / experimental** → *Sub-agent context references* |
| Surface-freeze switch | Assistant → **Basic Settings** → **Advanced / experimental** → *Freeze sub-agent tool surface* |
| Media hand-off | `subagent_dispatch` → `attach_media` |
| Expert namespace | `agents/<namespace>/` in the bound workspace |

---

## 5. Cold memory (a Markdown knowledge base)

**Why.** Flat `id + content` memory is always injected; a large knowledge base would pin
itself into every request.

**What it does**

- `memory_index` / `memory_read` / `memory_write` operate on a **folder of Markdown** inside
  the bound workspace. Documents stay on disk and are pulled in **on demand**, so a large
  knowledge base costs nothing until it is read. Off by default.

**Where it lives**

| Thing | Path / name |
|---|---|
| Tools | `memory_index`, `memory_read`, `memory_write` |
| UI | Assistant → **Memory** → **Cold memory (Markdown)** |
| Folder | a folder inside the bound workspace (bind one in Assistant → **Basic Settings**) |

---

## 6. Workflows: data flow and secrets

**Why.** Workflow actions could not pass data to each other (no `{{…}}` anywhere), and
credentials had to be inlined in plain text into a definition that is stored, rendered back
to the model, and kept in run history.

**What it does**

- **Action data-flow** — an action can reference an earlier one:
  `{{actions[0].text}}`, `{{actions[0].json.a.b[2].c}}`.
- **Secrets store** — AES/GCM + Android Keystore backed, referenced as `{{secret:NAME}}`,
  allowed **only** in `web_fetch` headers, and never readable by the model.
- **More verbs** — `web_fetch` grew from GET/POST to **GET/POST/PUT/PATCH/DELETE/HEAD**, with
  the correct body rules.
- All of it sits behind a **per-workflow** `useActionTemplates` flag that **defaults to off**;
  with the flag off, no argument is even scanned.

**Where it lives**

| Thing | Path / name |
|---|---|
| Flag | `WorkflowDefinition.useActionTemplates` (per workflow, default off) |
| Secrets UI | Settings → **Workflows** → open one → **API secrets** |
| Tool | `web_fetch` |

---

## 7. Headless safety fixes

**Why.** Background paths (cron, workflows) run with nobody to approve a prompt, and upstream
resolved that by auto-approving *everything* — bypassing the never-auto-allow floor.

**What was fixed**

- **Never-auto-allow respected in headless runs** — tools on the never-auto-allow list are no
  longer silently approved when a background run fires them.
- **Privacy / on-behalf tools gated** — the subset of tools that act *as you* (messaging,
  posting, sending) is no longer auto-approved on headless paths.
- **Retry idempotency corrected** — a bug treated the whole `web_` prefix as read-only and
  idempotent, so `web_fetch` **writes** (POST / PUT / PATCH / DELETE) could be retried as if
  they were GETs. Now only genuinely read-only calls are retried.

---

## Appendix · complete index

**Tools added by this fork**

| Tool | Purpose |
|---|---|
| `tool_search` / `tool_open` | Progressive tool exposure (opt-in) |
| `compact_context` | Model-initiated compaction (opt-in) |
| `memory_index` / `memory_read` / `memory_write` | Cold memory (opt-in) |
| `subagent_dispatch` / `subagent_get` / `subagent_list` / `subagent_cancel` / `subagent_tool` | Sub-agent control |
| `subagent_create` / `subagent_update` / `subagent_delete` | Expert library management |
| `usage_export` / `usage_get_prices` / `usage_set_prices` | Ledger export and price table |

**Switches added by this fork**

| Switch | Default | Location |
|---|---|---|
| `Assistant.disabledLocalTools` | — (per tool) | Assistant → Local Tools |
| `toolResultMaxTokens` | off | Assistant → Basic |
| Orchestration token budget | empty = unlimited | Assistant → Basic |
| Concurrent sub-agents | capped | Assistant → Basic |
| `compact_context` | off | Advanced / experimental |
| Tool-surface mode | off | Advanced / experimental |
| Sub-agent context refs | off | Advanced / experimental |
| Sub-agent surface freeze | off | Advanced / experimental |
| Cold memory | off | Assistant → Memory |
| `mcp_add` `enabled` | false | MCP tool schema |
| `mcp_update` `enabled` | keeps current | MCP tool schema |
| `WorkflowDefinition.useActionTemplates` | off | per workflow |

---

## 中文说明

### 概览

本 fork 完整保留上游能力，在其上叠加七件事，**全部默认关**、关闭时默认路径不变：

| # | 新增 | 一句话 |
|---|---|---|
| 1 | **用量账本与预算** | 每次模型调用按用途与成本记账；编排可设上限 |
| 2 | **按需工具暴露** | 可搜索的工具目录；只注入模型真正打开的 schema |
| 3 | **长任务存活** | 工具结果预算、模型主动压缩、执行级重试、保活 |
| 4 | **专家库** | 可复用子 agent，各有模型/工具/命名空间/记忆 |
| 5 | **冷记忆** | Markdown 知识库，按需读取而非常驻上下文 |
| 6 | **工作流数据流与密钥** | 动作间传值 + 加密密钥库 |
| 7 | **无头安全修复** | 堵住后台运行"全自动批准"的路径 |

### 1 · 用量账本与预算

独立 `usage_ledger.db`（只存元数据，**不存消息内容**）· 12 类用途归因 · **未报告字段记 `null` 而非 0** · 成本**写入时冻结**（`priceVersionId` + `costMicro`，可按当前价重算）· 按模型价目表（含**峰谷**）· 统计页按 **日/用途/模型/助手** 分组（近 90 天）+ **tok/s** · **编排树**（父→子，最近 50 次）· 编排 token 上限**派发前**检查、超限回**结构化信封**。工具：`usage_export` / `usage_get_prices` / `usage_set_prices`。

### 2 · 按需工具暴露

**工具调色板**（Settings → Sub-agent profiles → Tool palette，可搜索；**56 组 / 约 180 个工具**）· **逐工具开关**（`Assistant.disabledLocalTools`）· 渐进暴露（`tool_search` / `tool_open`，默认关）· 工具面装配 **6 → 1 个单点**。

### 3 · 长任务存活

工具结果预算（超长结果留**头+尾**，全文落 `/tool_outputs/` 按需回读；默认 32 KB 溢出闸）· **模型主动压缩**（`compact_context`，默认关）· **工具执行级重试**（幂等白名单）· 编排期 **FGS 保活**。

### 4 · 子 agent 与专家库

**上下文交接**（可带最近若干轮或音视频；纯文本，子 agent 仍读不到父会话）· **专家库**（`AgentDefinition`，Room 持久化；每位专家有自己的 system prompt、模型、工具面、MCP、skills，及**独立命名空间** `agents/<namespace>/`——含私有冷记忆目录）· **工具面冻结**（子 agent 只见无头安全子集）· **三种执行强度**（全能 / 混合 / 一父多子，正交旋钮）· **音视频输入**（`attach_media`）。工具：`subagent_dispatch/get/list/cancel/tool/create/update/delete`。

### 5 · 冷记忆

`memory_index` / `memory_read` / `memory_write` 操作工作区里的 Markdown 目录；按需读取，知识库再大也不占常驻上下文。默认关。入口：Assistant → Memory → Cold memory（需先绑工作区）。

### 6 · 工作流

动作间数据流 `{{actions[0].text}}`、`{{actions[0].json.a.b[2].c}}` · 密钥库（AES/GCM + Keystore，`{{secret:NAME}}`，**仅允许**出现在 `web_fetch` 的 header，模型永远读不到值）· `web_fetch` 动词扩到 **6 个** · 全部藏在**每工作流**的 `useActionTemplates` 开关后，默认关。

### 7 · 无头安全修复

无头运行**尊重** never-auto-allow 底线 · **隐私/代发类工具**不再被自动放行 · 修掉「把整个 `web_` 前缀当幂等、导致 `web_fetch` 写请求被当 GET 重试」的 bug。
