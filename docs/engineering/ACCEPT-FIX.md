# PHASE2 验收修复批次（ACCEPT-FIX）· 2026-10-03

> 来源：`QA-PHASE2.md` §D 验收发现的问题。用户指令「一次解决」，范围经确认。
> 工法沿用 `p2xx/` 骨架：`patch.py`（唯一性断言 + already_marker 守卫）→ `put.sh` 上 VPS `/tmp/p2xx` →
> VPS 执行 patch（repo `/tmp/pure-t01`）→ `harness.py`（本地 kotlinc）→ `selfcheck.py` → `push.sh` →
> `mkpr.py` → `check_ci.py` → `merge_pr.py`。**CI 是唯一门禁**（assembleDebug + testDebugUnitTest）。

## 范围（用户确认）
纳入：**D11 · D8/D9 · D6 · D1 · D12 · D4 · D5**
排除：D7、D10（UI 中等，可后续）；D2/D3/D13（客户端能力缺失，另立产品卡）。

## 卡表与状态
| 卡 | 条目 | 根因 | 状态 |
|---|---|---|---|
| P2-16 | D11 | `OrchestrationTree.build` join 键 `run.id` vs 账本 `domainId` | ✅ PR#37 合并 `13d518c0` |
| P2-18 | D1 | 统计卡读 `message.usage`（末次）vs 账本（逐次） | ✅ PR#39 合并 `b3436497` |
| P2-17 | D12 | 提供商列表不按 enabled 置顶 | ✅ PR#38 合并 `7a09b759` |
| P2-19 | D6 | footer 缺 tok/s（分母＝生成时长）| ✅ PR#40 合并 `bafddbe2` |
| P2-20 | D4 | 四个能力开关无中文（i18n 半）| ✅ PR#41 合并 `44a5db0b` |
| P2-21 | D8/D9 | 专家继承面 UI 不可见/易误读 | ✅ PR#42 合并 `1dec9d2d` |
| P2-22 | D5 | 安装类工具硬拒 → 装=落盘+默认 disabled | ✅ PR#43 合并 `9ff6821c` |

> 编号顺序＝实际开工顺序（P2-17 为 D12、P2-18 为 D1，与最初预估表相反）。

## 关键决定与遗留
- **D6 分母**：用 `usage_records.latency_ms`（每次调用延迟）之和——账本一行＝一次模型往返，工具在往返**之间**执行 ⇒ 天然排除工具时间。统计页维度卡的 tok/s **已由 P2-26 收口**（PR #47 / squash `e45e383c`，2026-10-04）：`UsageStatBucket` 加 `measuredOutputTokens` / `generationMs` / 计算属性 `tokensPerSecond`，按日/用途/模型/助手四个维度卡全部显示；无人上报延迟则不显示。
- **D1 口径**：顶部 token 卡改读账本 `total`（与下方账本同源）；计数（对话/消息/启动）仍留消息库；未加载时回退旧值。
- **D4 分组半**：`AssistantBasicPage.kt` 是**无分组头的扁平 FormItem 列表**，归入「高级/实验」是整页重构，另立卡。本卡只补 zh + zh-rTW 共 8 条 i18n。
  → ✅ **P2-28 已收口（2026-10-04，PR #49 / `664bba35`）**：四个协议级开关**逐字**移入独立卡，卡首标题「高级 / 实验功能」+ 副标题「协议级 / 实验性开关，默认全部关闭…」（风险提示 + off-by-default 标注），微调位置＝紧跟大卡之后。开关本体零改动，新增 2 串 × 三语。（用户拍板 A＝页内拆独立卡，非独立 tab。）
- **D8/D9 证据**：设备专家库实查 4 个专家全 `local_tools=NULL`、仅 `slug` 有值 ⇒ 继承父助手整张工具面；运行时本就正确，修的是 UI 诚实度（卡片常显徽章 + 编辑器明确「继承＝父的完整面」）。
- **D5 语义**：装=落盘+默认 disabled。`mcp_update` 省略 `enabled` 时仍默认 true（保留旧行为，已记 PR#43 待办）。
  → ✅ **P2-29 已收口（2026-10-04，PR #50 / `695778a3`）**：改用「更新≠安装」口径 —— 省略 `enabled` 时默认**保留该服务器当前状态**（`old.commonOptions.enable`），显式值仍优先；顺带修正 `mcp_add` 的 prose/schema（P2-22 漏改的 *default true* → *false*）与 `mcp_update` 缺失的 `enabled` 参数描述。（用户拍板 A＝保留原状态。）

## 本批全部收口（2026-10-04）
7 卡 7 PR **全部 merged**（PR #37~#43）：P2-16 `13d518c0` / P2-17 `7a09b759` / P2-18 `b3436497` / P2-19 `bafddbe2` / P2-20 `44a5db0b` / P2-21 `1dec9d2d` / P2-22 `9ff6821c`。

## 后续卡片（本批之外的验收遗留，均已 merged）
| 卡 | 条目 | 交付 | 结果 |
|---|---|---|---|
| P2-23 | D7（主体）| 子智能体会话归档进**受保护文件夹**（`Settings.subAgentArchiveFolders`，保护＝是目标且非空）| PR#44 `84e2a288` |
| P2-24 | D7（第二入口）| 助手设置 Basic 页同源选归档夹 | PR#45 `8ef376bb` |
| P2-25 | D10 | 统计页改版：删 52 周热力图，新增近 30 天堆叠柱状图 + 调用次数折线 | PR#46 `5a591ed2` |
| **P2-26** | **D6（剩余）** | **统计页四维卡补 `tok/s`** | PR#47 `e45e383c` |
| **P2-27** | **D3** | **音视频入口 + Chat Completions 传输 + 子智能体 `attach_media`** | PR#48 `100f226e` |
| **P2-28** | **D4（分组半）** | **四个协议级开关拆入「高级 / 实验功能」独立卡 + 三语 2 串** | PR#49 `664bba35` |
| **P2-29** | **D5（残留）** | **`mcp_update` 省略 `enabled` 时保留原状态 + 文档修正** | PR#50 `695778a3` |

**当前 master＝`695778a3`，50 PR 全 merged，0 open PR / 0 open issue。** ★ D4 / D5 至此**全部收口**（i18n 由 P2-20，安装类放行由 P2-22）。
