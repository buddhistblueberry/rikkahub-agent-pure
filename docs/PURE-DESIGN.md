# Design notes

> Why this fork exists, the rules it holds itself to, and how the work was sequenced.
> For the feature detail see [PURE-FEATURES.md](PURE-FEATURES.md); for the per-change
> engineering logs see [`docs/engineering/`](engineering/).

## Why another fork

```
rikkahub/rikkahub                      the original native Android LLM chat client
   └─ ExTV/rikkahub-agent              adds the agent layer: device tools, workflows,
        │                              Shizuku/Termux shells, sub-agents, Telegram bot…
        └─ wuyhong715/rikkahub-agent-pure   ★ this repo — the "Pure" hardening pass
```

Upstream's agent layer was built feature-first: the goal was to *add capabilities*, not to
make a long unattended run survive its own output. Six things got in the way:

| Symptom | In upstream |
|---|---|
| Every enabled tool's schema is injected every turn | no on-demand tool loading |
| Compaction only fires on a token threshold | the model can't compress on its own |
| One `logcat` dump can flood the context | tool results were unbounded |
| Sub-agents receive only a bare `task` string | no context hand-off |
| Tokens were counted, but never attributed | no per-purpose ledger, no cost, no cap |
| Headless paths (cron, workflows) auto-approved everything | the approval floor could be bypassed |

Pure does not remove features. It keeps the whole upstream surface and adds a layer that
answers one question: **can this run for hours without blowing up the context or the bill?**

## The rules it holds itself to

These are not aspirations — they are enforced on every change.

1. **Off by default, and unchanged when off.** Every switch this fork adds defaults to off,
   and with the switch off the default path behaves as before. Several paths are literally
   *byte-for-byte* identical when disabled. *(Default path = the upstream all-in-one route.)*
2. **New files over edits to the hot paths.** Change `ChatService.kt` and
   `GenerationLoop.kt` as little as possible, so upstream stays mergeable.
3. **One branch, one PR, CI-gated.** Nothing merges unless `assembleDebug` *and*
   `testDebugUnitTest` are green.
4. **The ledger stores metadata only** — tokens, cost, ids. **Never message content.**
5. **Unknown ≠ zero.** A field the provider did not report stays `null`; it is never counted
   as `0` in statistics.
6. **A budget overrun is a structured envelope, not a silent failure.** The model gets a
   machine-readable reason it can act on.
7. **AGPL-3.0**, inherited from upstream.

## The three execution strengths

The original design question was *"should the parent always run with a thin tool surface?"*
We decided **no** — and rejected a mutually-exclusive mode enum. Instead the behaviour is
built from **orthogonal knobs**, which combine into three common shapes without being
defined by them:

| Shape | Parent tools | Sub-agents | Typical use |
|---|---|---|---|
| **All-in-one** | full | none | one long task, one context |
| **Hybrid** | full | a few, on demand | the parent debugs while helpers fetch |
| **One-parent-many-children** | can be thin | parallel fan-out | "research these five things at once" |

The knobs: parent tool surface · whether to dispatch at all · where children come from
(named experts vs. ad-hoc) · orchestration budget · keep-alive.

## How the work was sequenced

Two phases, one PR at a time, each verified by CI before the next began.

**Phase 1 — the capabilities** (12 cards). Tool-result token limiting · model-initiated
compaction · progressive tool exposure · sub-agent context hand-off · sub-agent tool-surface
freezing · execution-level retry · cold memory · workflow data-flow and secrets · and three
headless-safety fixes. The first card was CI itself: without a build gate, every later card
would have stalled at "does this even compile?".

**Phase 2 — the ledger and the team** (17 cards + an acceptance-fix batch). A metered usage
ledger (capture → storage → cost freezing → export → price table) · collapsing the tool
surface to a single assembly point · per-tool switches · the tool palette · orchestration
budgets and keep-alive · the expert library · the statistics page and orchestration tree ·
and the fixes found by installing the build on a real device.

Every card was cut as *one branch, one PR*, gated by the same CI. The complete log, per card,
is in [`docs/engineering/PHASE2.md`](engineering/PHASE2.md).

## Deliberately not done

- **No mode enum.** Execution strength is knobs, not a radio button.
- **No relaxed recursion guard.** Orchestration stays one level deep, all of it brokered
  through the parent, until a real chained requirement shows up.
- **Media forwarding is scoped.** Images are not forwarded into sub-agents, and the media
  work deliberately left the Responses API, Claude and Google paths untouched.
- **Video generation stays a thin protocol layer.** The retired `videogen` module sketched a
  much wider capability model (last frame, reference image/video/audio, document and web-page
  input, callback URLs, seed/watermark/audio knobs, usage accounting). Those stay
  unimplemented until a real call for them shows up — see
  [VIDEO-GENERATION.md](VIDEO-GENERATION.md).
- **No daily spend cap (yet).** The per-orchestration ceiling shipped; a calendar-day cap
  was deferred.

## Verification

There is **one gate: CI.** `assembleDebug` plus `testDebugUnitTest` — currently
**2,260+ unit tests, 0 failures**. There is no lint job. Device-only behaviour (Compose,
Room, OkHttp, Keystore) is confirmed by installing the CI artifact and exercising it, and
the findings from that pass are kept in
[`docs/engineering/QA-PHASE2.md`](engineering/QA-PHASE2.md).

---

## 中文说明

### 为什么再 fork

血统：`rikkahub/rikkahub` → `ExTV/rikkahub-agent` → 本仓库。上游 agent 层是**功能优先**建的，没为"长任务自身的输出"兜底，于是有六个卡点：工具 schema 每轮全量注入 / 压缩只能靠阈值 / 工具结果无上限 / 子 agent 只收一个 `task` 字符串 / token 有计数无归属 / 无头路径全自动批准。

「纯化」**不删功能**：保留上游全部能力，只加一层，回答一个问题——**能不能跑几个小时，而不炸上下文、不炸钱包？**

### 七条自我约束（每条都真的在改动静上执行）

1. **默认关，关了就等价于没改**（若干路径逐字节相同）。
2. 改动**优先新增文件**，少动 `ChatService.kt` / `GenerationLoop.kt`，保证可持续合并。
3. **一分支一 PR**，`assembleDebug` + `testDebugUnitTest` 全绿才合。
4. 账本**只存元数据**，绝不存消息内容。
5. **未知 ≠ 0**：provider 未报告的字段记 `null`，不按 0 统计。
6. **超预算回结构化信封**（机器可读），不静默失败。
7. AGPL-3.0。

### 三种执行强度

不是互斥模式，而是**正交旋钮**组合出的三种常见形态：

| 形态 | 父工具面 | 子 agent | 典型场景 |
|---|---|---|---|
| **全能** | 全开 | 不派 | 一个长任务、一份上下文 |
| **混合** | 全开 | 按需派几个 | 父在调试，助手去取料 |
| **一父多子** | 可收窄 | 并行扇出 | "同时查这五件事" |

旋钮：父工具面 · 是否派发 · 子来源（专家 vs 临时）· 编排预算 · 保活。

### 工作如何编排

**一期＝能力**（12 张卡）：工具结果限流 · 模型主动压缩 · 渐进式工具暴露 · 子 agent 上下文交接 · 工具面冻结 · 执行级重试 · 冷记忆 · 工作流数据流与密钥 · 三个无头安全修复。第一张卡就是 CI 本身——没有构建门禁，后面每张卡都会卡在"改完不知道能不能编译"。

**二期＝账本与团队**（17 张卡 + 验收修复批次）：用量账本（采集→存储→成本冻结→导出→价目表）· 工具面装配收成单点 · 逐工具开关 · 工具调色板 · 编排预算与保活 · 专家库 · 统计页与编排树 · 以及真机装机后发现的修复。

### 刻意不做

不做互斥模式枚举 · 不放松递归卫（编排维持一层、全部经父中转，待真实链式需求）· 图片不进子智能体、媒体改造不碰 Responses/Claude/Google · 每日消费上限后置。

### 验证

**唯一门禁是 CI**：`assembleDebug` + `testDebugUnitTest`（**2260+ 单测全绿**，无 lint job）。只有真机才能验证的行为（Compose / Room / OkHttp / Keystore）靠装 CI 产物实测，结论记在 [`docs/engineering/QA-PHASE2.md`](engineering/QA-PHASE2.md)。
