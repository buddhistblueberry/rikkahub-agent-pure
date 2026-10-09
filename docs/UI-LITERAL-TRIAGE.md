# Chinese string literals in Kotlin — triage

> Companion to [PROVIDER-LOCALIZATION.md](PROVIDER-LOCALIZATION.md). Status: **batch 1 landed**,
> batches 2-4 open. Numbers below were produced by scanning `*/src/main/java/**/*.kt`, counting
> CJK inside string literals with comments blanked out.

## The headline number is misleading

A naive `grep` finds ~284 CJK hits in Kotlin. That overstates the problem badly:

| Filter | Count |
|---|---|
| CJK anywhere in `.kt` (incl. comments) | 284 |
| CJK inside a string literal, comments excluded | 198 |
| …of which are Compose `@Preview` sample data | 10 |
| …**actually user-visible UI text** | ~100 |

Roughly **half** of the "Chinese in the UI" is not UI at all. Translating it would be wasted
work at best and broken behaviour at worst.

## Must NOT be translated

| What | Where | Why |
|---|---|---|
| TTS voice IDs + their romanised handles | `TTSProviderConfigure.kt` | literal API values; `冰糖`, `优雅女生 (youyanvsheng)` — the pinyin is the identifier |
| Icon match patterns | `AIIconMatcher.kt` (13) | `Regex("moonshot\|月之暗面")` — matching logic, not copy |
| Synthetic debug/test data | `DebugVM.kt` (27) | random word fragments used to *generate* fake conversations; `用户`/`助手` are role prefixes |
| `Accept-Language` header | `common/http/AcceptLang.kt` | deliberately Chinese |
| AI system prompts | `SearchService.kt`, `GoogleProvider.kt`, `QwenTTSProvider.kt`, `ChatCompletionsAPI.kt` | translating changes model behaviour |
| MCP protocol messages | `McpOAuthCoordinator.kt` (10) | OAuth error payloads |
| `@Preview` sample data | `Tag.kt` (5), `CardGroup.kt` (4), `DataTable.kt` (1) | never shipped; IDE-only |
| User-created provider names | — | the user's own text |

## Batches

| # | Scope | Strings | Status |
|---|---|---|---|
| 1 | `SettingMcpPage`, `WorkspaceFileEditorPage`, `ASRProviderConfigure`, the `火山引擎`→Volcengine labels in `SettingTTSPage` / `SettingSpeechPage` / `TTSProviderConfigure` | 12 | **done** |
| 2 | `DebugPage.kt` (23) — buttons, toasts, status text. Needs interpolation → `stringResource(R.string.x, arg)` | 23 | open |
| 3 | `WorkspaceDetailVM.kt` (14) — toast/error messages, all interpolated | 14 | open |
| 4 | `TTSProviderConfigure.kt` remaining labels (~35): model annotations, format/speed/volume/sample-rate labels, API-key hints | ~35 | open |
| 5 | Provider `description` / `shortDescription` marketing copy in `DefaultProviders.kt` (11) + `RecommendedProviders.kt` (4) | ~15 | open |

## Implementation notes that will bite otherwise

- **Strings used inside coroutines cannot call `stringResource`.** `WorkspaceFileEditorPage` shows
  toasts from inside `scope.launch`, and reads a file from inside `LaunchedEffect` — neither is a
  composable scope. These are hoisted to `val`s in composition first and captured by the lambda.
  Batches 2 and 3 are almost entirely this shape, because toasts and status messages live in
  coroutines.
- **`when` expressions used as a `Text` argument are fine**, but `optionToString` in
  `components/ui/Select.kt` is declared `@Composable (T) -> String`, so `stringResource` is legal
  inside it. Worth knowing before assuming either way.
- Locales other than `zh` fall back to the base English, so `ja`/`ko`/`ru`/`ar` improve
  automatically as each key is added.
- `火山引擎` uses a dedicated key (`tts_setting_provider_volcengine`) rather than
  `provider_brand_*` from PR #2, so the two branches do not collide on merge.
