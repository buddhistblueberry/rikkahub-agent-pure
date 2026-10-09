# Provider name & description localization — design

> Status: **Tier 2 (brand names) implemented** in `feat/provider-brand-localization`.
> Tier 1 (descriptions) and Tier B (resellers) are still open - see below.
> Related: [PURE-DESIGN.md](PURE-DESIGN.md). Follows rule 2 of that doc (new files over
> hot-path edits) and rule 3 (nothing merges without a green `assembleDebug`).

## Problem

The bundled default providers are named and described in Chinese. A user whose app
language is English, Japanese, Korean or Russian sees a Chinese provider list. This is
the same class of bug as PR #1 (untranslated pricing strings in the base resource file),
but it lives in Kotlin rather than in `values/strings.xml`, and it is *not* fixable by
editing a resource file.

Two distinct kinds of text are involved, with different mechanisms and different risk.

## What was verified

### Descriptions — already refresh from defaults, so they are the easy half

`ProviderSetting.description` and `shortDescription` are declared as
`@Composable () -> Unit` (`ai/.../provider/ProviderSetting.kt`) — composable lambdas,
not strings. `PreferencesStore.kt` (~L643-651) re-copies `builtIn`, `description` and
`shortDescription` from `DEFAULT_PROVIDERS` on every read:

```kotlin
providers = providers.map { provider ->
    val defaultProvider = DEFAULT_PROVIDERS.find { it.id == provider.id }
    if (defaultProvider != null) {
        provider.copyProvider(
            builtIn = defaultProvider.builtIn,
            description = defaultProvider.description,
            shortDescription = defaultProvider.shortDescription,
        )
    } else provider
}
```

Because the field is a composable lambda, its body can call `stringResource(...)` and
resolve against the current locale on its own. **No migration, and it applies
retroactively** to installs that already have these providers on disk.

### Names — persisted, and deliberately excluded from that refresh

`name` is a plain persisted `String` and is *not* in the refresh list above, because it
is user-owned: it is editable in the provider config screen. So editing the constant
changes **new installs only**; everyone who already launched keeps their stored copy.

The fix is still cheap, for one specific reason — **that merge is an in-memory
projection, never written to disk.** It ends in `it.copy(providers = providers, ...)`
inside a `.map { }`, so nothing is persisted. Therefore no destructive data migration is
needed at all: the localized name is substituted at read time and simply recomputed next
launch.

One constraint follows from this. `DEFAULT_PROVIDERS` is a top-level `val listOf(...)`
built at class-init, long before any locale is known, and `name` is an eagerly-typed
`String`. A localized name therefore **cannot** live in that list — it has to be resolved
where the locale is actually available.

## Design

### Tier 1 — descriptions (low risk, retroactive)

Replace the literal `Text("…")` calls inside each `description` lambda with
`stringResource(R.string.…)`, and add the keys to `values/strings.xml` (English) and
`values-zh/strings.xml`. Everything else in the mechanism stays as-is.

### Tier 2 — brand names (needs a display-site helper)

Do **not** put the localized name in `DEFAULT_PROVIDERS`. Instead add a small composable
helper in the UI layer:

```kotlin
@Composable
fun ProviderSetting.displayName(): String = when (name) {
    // stored value still equals the legacy default -> show the localized brand
    LEGACY_DEFAULT_NAMES[id] -> stringResource(defaultNameRes(id))
    else -> name // user renamed it; hands off
}
```

and route provider-name display through it.

- **Safety property:** substitution only happens when the stored `name` is *exactly* the
  old Chinese default, so a user who renamed a provider is never overridden. A user who
  deliberately renamed it *back* to the Chinese name would get the Latin brand — an
  acceptable, self-correcting edge case.
- **Preferred call site:** the Compose display site, not the `PreferencesStore` merge. The
  merge runs in a non-composable `Flow.map` (no `stringResource` available) and only
  re-emits when the datastore changes, so a name resolved there would go stale when the
  user switches app language until the next write or restart. Resolving in composition
  recomposes correctly and is immediately reactive.
- Cost: `displayName()` has to be adopted at each site that renders a provider name, so
  the work should be audited with a grep for `.name` on provider settings rather than
  done blind.

## The open question: should brand names be translated *per locale*?

The request was "English in English, Russian in Russian". For **descriptions** that is
straightforward and I recommend it — full translation into `en / ja / ko / ru / ar`.

For **brand names** I would push back, and this is the main thing needing a decision.

Brand names are trademarks, and the overwhelming convention (OpenRouter, LiteLLM, the
major LLM clients) is: **the Latin brand name in every locale, except Chinese**, where
the Chinese name is what users actually recognize. These vendors do not publish Russian
or Japanese brand names. Translating them produces invented forms that users cannot
match to the vendor:

| Stored | Latin brand | Naive Russian literal |
|---|---|---|
| 硅基流动 | SiliconFlow | «Кремниевый поток» — unrecognizable |
| 月之暗面 | Moonshot AI | «Тёмная сторона луны» — a different company entirely |

So the recommendation is to split the two tiers:

- **Names** → official Latin brand for every locale *except* `zh`.
- **Descriptions** → genuinely translated per locale.

If Russian brand names are genuinely wanted, they should be sourced from each vendor's own
Russian marketing rather than invented. That is research work, not a translation pass.

## Inventory (verified against each entry's `baseUrl`)

**Tier A — official bilingual brands. Latin name everywhere except `zh`:**

| Stored name | UUID | `baseUrl` | Latin brand |
|---|---|---|---|
| 硅基流动 | `56a94d29…` | api.siliconflow.cn | SiliconFlow |
| 月之暗面 | `d6c4d8c6…` | api.moonshot.cn | Moonshot AI |
| 阿里云百炼 | `f76cae46…` | dashscope.aliyuncs.com | Alibaba Cloud Model Studio |
| 火山引擎 | `3dfd6f9b…` | ark.cn-beijing.volces.com | Volcengine |
| 智谱AI开放平台 | `3bc40dc1…` | open.bigmodel.cn | Zhipu AI (BigModel) |
| 阶跃星辰 | `f4f8870e…` | api.stepfun.com | StepFun |
| 腾讯Hunyuan | `ef5d149b…` | api.hunyuan.cloud.tencent.com | Tencent Hunyuan *(already half-Latin)* |

**Tier B — no official English identity. Deliberately out of scope pending a decision:**

| Stored name | UUID | `baseUrl` | Note |
|---|---|---|---|
| 小马算力 | `da020a90…` | api.tokenpony.cn | domain says TokenPony; unclear which is the real brand |
| 随想AI网关 | `aecf04fd…` | sui-xiang.com | relay/reseller, no English name |

These two also carry the long Chinese marketing blurbs. **Unresolved:** an English user
currently sees a wall of Chinese text here, and none of the options are obviously right
— leave as-is, hide from non-`zh` users, or show a generic label with the original as a
subtitle. Recorded as an open product question rather than guessed at.

## Also in scope-adjacent code

`ui/pages/setting/components/TTSProviderConfigure.kt` maps every TTS provider to an
English label **except** `TTSProviderSetting.Volcengine -> "火山引擎"` (two occurrences).
A one-line inconsistency, independent of everything above.

## Explicitly NOT to be translated

These appear in the same files and must be left alone — translating them breaks behavior:

- TTS voice IDs returned by provider APIs (冰糖, 茉莉, …) — literal API values.
- `common/http/AcceptLang.kt` — builds the `Accept-Language` header; Chinese is deliberate.
- Chinese text embedded in AI system prompts — translating it changes model behavior.
- `name` of user-created (non-`builtIn`) providers — that is the user's own text.

## Risks

- `displayName()` is opt-in per call site, so partial adoption leaves a mixed list. Audit
  by grep, not by eye.
- App-language changes may not force a datastore re-emit; resolving in composition is
  what avoids a stale-name bug.
- Translation quality for the marketing blurbs is unreviewed — these need a native
  speaker, not a machine pass.

## Open questions

1. **Names per locale, or Latin-except-zh?** See above; this is the blocking decision.
2. **Tier B resellers** — leave, hide, or generic label?
3. Who reviews the `ja / ko / ru / ar` description translations?
