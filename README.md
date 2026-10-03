<div align="center">

<img src="docs/icon.png" width="96" height="96" alt="RikkaHub Agent" style="border-radius: 24px" />

# RikkaHub Agent · Pure

**An Android LLM client turned into a long-running on-device agent — one that can account for what it spends.**

A fork of [ExTV/rikkahub-agent](https://github.com/ExTV/rikkahub-agent) (itself a fork of [rikkahub/rikkahub](https://github.com/rikkahub/rikkahub)), hardened for autonomous, multi-hour tasks: on-demand tool exposure, model-initiated compaction, a metered token & cost ledger, reusable experts, and deep sub-agent orchestration. **Every addition is opt-in and off by default.**

<p>
  <a href="https://github.com/wuyhong715/rikkahub-agent-pure/actions/workflows/build.yml"><img src="https://github.com/wuyhong715/rikkahub-agent-pure/actions/workflows/build.yml/badge.svg" alt="Build" /></a>
  <img src="https://img.shields.io/badge/platform-Android%208%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 8+" />
  <img src="https://img.shields.io/badge/license-AGPL--3.0-blue?style=flat-square" alt="AGPL-3.0" />
  <img src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin" />
</p>

<a href="#what-is-this">English</a> · <a href="#中文说明">简体中文</a> · <a href="#what-the-pure-fork-adds">What's added</a> · <a href="#building">Build</a>

</div>

---

## What is this?

Three forks, one lineage:

```
rikkahub/rikkahub                    the original native Android LLM chat client
   └─ ExTV/rikkahub-agent            adds the agent layer: device tools, workflows,
        │                            Shizuku/Termux shells, sub-agents, Telegram bot…
        └─ wuyhong715/rikkahub-agent-pure   ★ this repo — the "Pure" hardening pass
```

**Pure does not remove features.** It keeps the entire upstream surface and adds one layer on top, aimed at a single question:

> Can this thing run unattended for a long time without either blowing up the context or blowing up the bill?

So the work concentrates on four things: **cost you can see**, **context you control**, **runs that survive**, and **teams of agents that behave**.

## Why another fork

Upstream's agent layer was built feature-first. Six things got in the way of long autonomous runs:

| Symptom | In upstream |
|---|---|
| Every enabled tool's schema is injected every turn | no on-demand tool loading |
| Compaction only fires on a token threshold | the model can't compress on its own |
| One `logcat` dump can flood the context | tool results were unbounded |
| Sub-agents receive only a bare `task` string | no context hand-off |
| Tokens were counted, but never attributed | no per-purpose / per-model ledger, no cost |
| Headless paths (cron, workflows) auto-approved everything | the approval floor could be bypassed |

## What the Pure fork adds

### 1. A usage ledger that actually balances

- **Independent `usage_ledger.db`** — a Room database kept out of the chat DB. Stores **metadata only** (tokens, cost, ids); never message content.
- **Per-call attribution by purpose** — 12 buckets (main, tool loop, compaction, memory, sub-agent, scheduled, workflow, skill test, translation, …), so you can see *where* the tokens went.
- **Honest accounting** — a field the provider did not report stays `null`; it is never counted as `0`.
- **Frozen cost** — each row records the price version it was priced with (`priceVersionId`) plus the computed `costMicro`, with a "recalculate at current prices" action. Prices come from a per-model table that understands peak / off-peak windows.
- **Export & control tools** — `usage_export` writes per-call CSV/JSON into `/workspace/exports/` (delta-capable via `since`); `usage_get_prices` / `usage_set_prices` let the agent read or replace the price table (approval-gated and audited).
- **Statistics page** — the ledger grouped by day / purpose / model / assistant over the last 90 days, with **tok/s** per bucket, plus an **orchestration tree** showing parent → child sub-agent runs.
- **Budgets that are enforced** — a per-orchestration token ceiling (the parent turn *and* every descendant it fans out), checked **before** dispatch. Exceeding it returns a structured, model-readable envelope instead of failing silently. An expert can override the ceiling, and concurrency is capped.

### 2. Tools loaded on demand, not all at once

- **Tool palette** — search the local tool directory from Settings and see which group a tool belongs to before enabling it. **56 tool groups, 80+ individual tools.**
- **Per-tool switches** — tool control used to be per *group*; the fork adds `disabledLocalTools`, so you can switch off individual tools inside an enabled group.
- **Progressive exposure** — an opt-in tool-surface mode swaps the MCP tools in each request for a `tool_search` / `tool_open` pair, so only the schemas the model actually opens are sent. (Cuts first-turn tool tokens; the disclosed trade-off is that a changing tool list can cost prompt-cache hits.)
- **One assembly point** — the six scattered places that used to assemble the tool surface were collapsed into one, so every knob composes predictably.

### 3. Long runs that don't fall over

- **Tool-result budget** — cap how much context one tool result may occupy; longer results keep their head and tail and spill the full text to `/tool_outputs/` for the model to read back on demand. (Default: 32 KB spill gate.)
- **Model-initiated compaction** — an opt-in `compact_context` tool lets the model summarise earlier turns by itself, reusing the same summariser as the manual compress action.
- **Tool-execution retry** — retries at the *execution* layer (not just the stream layer), behind an idempotency whitelist.
- **Orchestration keep-alive** — a sub-agent run holds a foreground service for its whole lifetime, so a long fan-out isn't killed when the app is backgrounded.
- **Headless safety floor** — closed the paths where cron / workflows (headless) auto-approved everything, ignoring the never-auto-allow floor and the privacy / on-behalf tools. Fixed a retry bug that treated `web_` **write** requests (POST / PUT / PATCH / DELETE) as idempotent GETs.

### 4. Sub-agents and a reusable expert library

- **Context hand-off** — `subagent_dispatch` can pass recent turns, or selected media, to a child, so a task that refers to what you were just discussing doesn't have to be retyped. The hand-off travels as plain text; the child still cannot read the parent conversation.
- **Expert library** — persistent, reusable sub-agent definitions (`AgentDefinition`, Room-backed, managed by `subagent_create` / `subagent_update` / `subagent_delete`). Each expert carries its own system prompt, model, tool surface, MCP servers, skills, and **its own namespace** under `agents/<namespace>/` in the workspace — including a private cold-memory folder. A group left on *inherit* simply uses whatever the dispatching assistant has.
- **Tool-surface freezing** — a sub-agent only ever sees the headless-safe part of the assistant's tools (device-UI tools and must-confirm tools are removed), and the dispatch call can narrow it further.
- **Three execution strengths** — all-in-one, hybrid, and one-parent-many-children — built from orthogonal knobs rather than a mutually-exclusive mode enum.
- **Audio / video input** — audio and video parts travel through the attachment picker, the Chat Completions transport, and into sub-agents (`attach_media`).

### 5. Cold memory (a Markdown knowledge base)

- `memory_index` / `memory_read` / `memory_write` operate on a folder of Markdown inside the bound workspace. Documents stay on disk and are pulled in on demand, so a large knowledge base costs nothing until it is read. Off by default.

### 6. Workflows that can chain and hold secrets

- **Action data-flow** — an action can reference an earlier one: `{{actions[0].text}}`, `{{actions[0].json.a.b[2].c}}`.
- **Secrets store** — AES/GCM + Android Keystore backed, referenced as `{{secret:NAME}}`, allowed only in `web_fetch` headers, and never readable by the model.
- **More verbs** — `web_fetch` grew from GET/POST to GET/POST/PUT/PATCH/DELETE/HEAD, with the correct body rules.
- All of it sits behind a **per-workflow** `useActionTemplates` flag that defaults to off; with the flag off, no argument is even scanned.

## The default contract

This fork has one rule it never breaks:

> **Every switch it adds is off by default, and with the switch off the default path is unchanged.** Several paths are literally byte-for-byte identical when disabled.

Alongside that:

- Changes prefer **new files** over edits to the hot paths (`ChatService.kt`, `GenerationLoop.kt`), so the fork stays mergeable against upstream.
- Every change ships as **one branch / one PR**, gated by CI — `assembleDebug` + `testDebugUnitTest`, currently **2,260+ unit tests, 0 failures**.
- AGPL-3.0 throughout.

## What's inherited from upstream

Everything ExTV's agent layer does still works, unchanged: 80+ device tools, Shizuku and Termux shells, AI-authored workflows and schedules, an in-app browser the AI drives, keyless web search and fetch, the Linux workspace with background tasks, SSH, media playback, skills, the Telegram bot, MCP servers, the Doctor health check, on-device LiteRT models, and the full provider set (OpenAI, Google, Anthropic, OpenRouter, Codex, Grok, Ollama, or any OpenAI-compatible endpoint).

For the complete feature tour, see the **[upstream README](https://github.com/ExTV/rikkahub-agent#features)**.

## Building

There are **no published releases** for this fork yet — builds come from source or from CI artifacts.

Requirements: JDK 17, the Android SDK (`platform-tools`), and [bun](https://bun.sh) + [pnpm](https://pnpm.io) on your `PATH` (bun installs the web-ui dependencies, pnpm builds the bundle).

```bash
git clone --recursive https://github.com/wuyhong715/rikkahub-agent-pure.git
cd rikkahub-agent-pure

./gradlew :app:assembleDebug      # -> app/build/outputs/apk/debug/*.apk
./gradlew :app:testDebugUnitTest  # unit tests
```

Or grab the `apk-debug` artifact from the latest [Actions run](https://github.com/wuyhong715/rikkahub-agent-pure/actions/workflows/build.yml). The debug variant carries the `.debug` application-id suffix (`excp.rikkahub.debug`), so it installs **side by side** with a release build of the upstream app.

| | |
|---|---|
| **Package** | `excp.rikkahub` |
| **Android** | 8.0+ (API 26), targets API 37 |
| **Version** | 2.5.1 (versionCode 187) |
| **Language** | Kotlin · Jetpack Compose · Room |

## Credits

Stands on the shoulders of giants:

| Project | Role |
|---|---|
| [RikkaHub](https://github.com/rikkahub/rikkahub) | The upstream chat client this ultimately forks |
| [ExTV/rikkahub-agent](https://github.com/ExTV/rikkahub-agent) | The direct upstream — the agent layer this builds on |
| [cron-utils](https://github.com/jmrozanec/cron-utils) | Cron parser for the scheduler |
| [whisper.cpp](https://github.com/ggerganov/whisper.cpp) | On-device speech-to-text via Termux |
| [Termux](https://github.com/termux/termux-app) | Shell + package manager |
| [JSch (mwiede fork)](https://github.com/mwiede/jsch) | Native SSH client |
| [FlorisBoard](https://github.com/florisboard/florisboard) | Base for the companion [agent-keyboard](https://github.com/ExTV/agent-keyboard) |

This fork is unaffiliated with the upstream RikkaHub or ExTV maintainers. All credit for the underlying chat client, provider abstraction, and UI design goes to them.

## License

GNU AGPL-3.0, inherited from upstream. See [LICENSE](LICENSE).

---

## 中文说明

### 这是什么

血统是一条线，不是三个项目：

```
rikkahub/rikkahub                    原始安卓 LLM 聊天客户端
   └─ ExTV/rikkahub-agent            加上 agent 层：设备工具、工作流、
        │                            Shizuku/Termux、子 agent、Telegram 机器人…
        └─ wuyhong715/rikkahub-agent-pure   ★ 本仓库 —— "纯化" 强化版
```

**「纯化」不删功能。** 它完整保留上游能力，只在其上加一层，只为一件事：

> 让 agent 能长时间无人值守地跑，而又不炸上下文、不炸钱包。

### 为什么要再 fork 一个

上游 agent 层是"功能优先"建的，有六件事挡着长任务：

| 症状 | 上游现状 |
|---|---|
| 每个启用的工具 schema 每轮全量注入 | 无按需加载 |
| 压缩只由 token 阈值触发 | 模型不能主动压 |
| 一条 `logcat` 就能灌满上下文 | 工具结果无上限 |
| 子 agent 只收到一个 `task` 字符串 | 无上下文交接 |
| token 有计数、无归属 | 无分用途/分模型账本，无成本 |
| 无头路径（cron、工作流）全自动批准 | 审批底线可被绕过 |

### 相对上游加了什么

**① 一本算得平的用量账本**
独立 `usage_ledger.db`（Room，独立库；只存元数据，绝不存消息内容）· 12 类用途归因 · **未报告字段记 `null` 而非 0** · 写入时冻结成本（`priceVersionId` + `costMicro`，可"按当前价重算"）· 按模型价目表（含峰谷）· `usage_export` 导出 CSV/JSON 到 `/workspace/exports/` · 统计页按 日/用途/模型/助手 分组 + **tok/s** · **编排树页**（父→子）· 编排 token 预算**派发前**检查、超限回结构化信封（给模型看，不静默失败）。

**② 工具按需加载**
**工具调色板**（可搜索本地工具目录，56 组 / 80+ 工具，能看到某工具属于哪组）· **逐工具开关**（`disabledLocalTools`，粒度从"组"细到"单个工具"）· 渐进暴露（`tool_search` / `tool_open` 换掉每轮的 MCP 全量 schema，默认关）· 工具面装配 **6 → 1 个单点**。

**③ 长任务不崩**
工具结果 token 预算（超长结果留头+尾，全文落 `/tool_outputs/` 按需回读）· **模型主动压缩**（`compact_context`）· 工具**执行级**重试（幂等白名单）· 编排期 FGS 保活 · 无头路径安全底线收口（并修掉了把 `web_` 写请求当幂等 GET 重试的 bug）。

**④ 子 agent 与可复用专家库**
上下文交接（可带最近若干轮或音视频，纯文本，子 agent 仍读不到父会话）· **专家库**（`AgentDefinition`，Room 持久化，`subagent_create/update/delete` 管理；每位专家有自己的 system prompt、模型、工具面、MCP、skills，以及**独立命名空间** `agents/<namespace>/`——含私有冷记忆目录；组设"继承"则沿用父助手）· 子 agent 工具面冻结（只见无头安全子集）· 三种执行强度（全能 / 混合 / 一父多子，正交旋钮而非互斥模式）· 音视频输入（`attach_media`）。

**⑤ 冷记忆**：`memory_index` / `memory_read` / `memory_write` 操作工作区里的 Markdown 目录，文档留在磁盘、按需拉取，知识库再大也不占常驻上下文。默认关。

**⑥ 工作流能串起来、能持密钥**：动作间数据流 `{{actions[0].text}}` 与 `{{actions[0].json.a.b[2].c}}` · 密钥库（AES/GCM + Keystore，`{{secret:NAME}}`，仅允许出现在 `web_fetch` 的 header，模型永远读不到值）· `web_fetch` 动词扩到 6 个 · 全部藏在**每工作流**的 `useActionTemplates` 开关后，默认关；关闭时连参数都不扫。

### 默认契约

> **新增的每个开关默认关，关闭时默认路径行为不变。** 有若干路径在关闭时逐字节相同。

配套：改动优先**新增文件**、少动主生成链（`ChatService.kt` / `GenerationLoop.kt`）以保证可持续合并 · 一分支一 PR，CI = `assembleDebug` + `testDebugUnitTest`（**2260+ 单测全绿**）· AGPL-3.0。

### 构建

本 fork **尚无发布版**，从源码或 CI 产物构建。需要 JDK 17、Android SDK（`platform-tools`）、以及 `PATH` 上的 [bun](https://bun.sh) 与 [pnpm](https://pnpm.io)。

```bash
git clone --recursive https://github.com/wuyhong715/rikkahub-agent-pure.git
cd rikkahub-agent-pure

./gradlew :app:assembleDebug      # 产物：app/build/outputs/apk/debug/*.apk
./gradlew :app:testDebugUnitTest  # 单元测试
```

或从最新 [Actions 运行](https://github.com/wuyhong715/rikkahub-agent-pure/actions/workflows/build.yml) 下载 `apk-debug` 产物。debug 变体带 `.debug` 后缀（`excp.rikkahub.debug`），可与上游 release 版**并存**安装。

### 上游功能

上游 ExTV 的全部能力原样保留（80+ 设备工具、Shizuku/Termux、工作流与定时、内置浏览器、免密钥网页搜索、Linux 工作区、SSH、音乐、skills、Telegram 机器人、MCP、Doctor 体检、本地 LiteRT 模型、全套 provider）。完整清单见 **[上游 README](https://github.com/ExTV/rikkahub-agent#features)**。

### 许可

GNU AGPL-3.0（继承自上游）。见 [LICENSE](LICENSE)。
