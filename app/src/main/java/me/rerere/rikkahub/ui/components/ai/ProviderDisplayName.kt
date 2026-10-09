package me.rerere.rikkahub.ui.components.ai

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.R
import kotlin.uuid.Uuid

/**
 * Locale-aware display names for the bundled (built-in) Chinese providers.
 *
 * `ProviderSetting.name` is persisted user data: it is editable in the provider
 * config screen and is deliberately NOT refreshed from `DEFAULT_PROVIDERS` by the
 * merge in `PreferencesStore` (only `builtIn`, `description` and `shortDescription`
 * are re-copied). Editing the constant in `DefaultProviders.kt` would therefore only
 * affect fresh installs, and existing users would keep the Chinese name forever.
 *
 * Instead we resolve the name at *display* time:
 *  - if the stored `name` still equals the frozen legacy default, show the localized
 *    brand resource instead;
 *  - otherwise the user renamed it deliberately, so leave it alone.
 *
 * The Latin brand lives in `values/strings.xml` and the Chinese name in
 * `values-zh/strings.xml`, so `stringResource` does the locale switch for us with no
 * explicit locale check: zh users get 简体中文, everyone else the Latin brand. This
 * matches how the rest of the app localizes, and matches the convention used by other
 * LLM clients (Latin brand everywhere except the Chinese market).
 *
 * `legacyName` is a frozen constant, not a resource - it is a migration marker and
 * must never be re-translated or updated.
 */
object BuiltInProviderBrands {

    private data class Brand(
        val legacyName: String,
        @StringRes val labelRes: Int,
    )

    private val brands: Map<Uuid, Brand> = mapOf(
        Uuid.fromString("56a94d29-c88b-41c5-8e09-38a7612d6cf8") to
            Brand("硅基流动", R.string.provider_brand_siliconflow),
        Uuid.fromString("d6c4d8c6-3f62-4ca9-a6f3-7ade6b15ecc3") to
            Brand("月之暗面", R.string.provider_brand_moonshot),
        Uuid.fromString("f76cae46-069a-4334-ab8e-224e4979e58c") to
            Brand("阿里云百炼", R.string.provider_brand_alibaba_bailian),
        Uuid.fromString("3dfd6f9b-f9d9-417f-80c1-ff8d77184191") to
            Brand("火山引擎", R.string.provider_brand_volcengine),
        Uuid.fromString("3bc40dc1-b11a-46fa-863b-6306971223be") to
            Brand("智谱AI开放平台", R.string.provider_brand_zhipu),
        Uuid.fromString("f4f8870e-82d3-495b-9b64-d58e508b3b2c") to
            Brand("阶跃星辰", R.string.provider_brand_stepfun),
        Uuid.fromString("ef5d149b-8e34-404b-818c-6ec242e5c3c5") to
            Brand("腾讯Hunyuan", R.string.provider_brand_tencent_hunyuan),
    )

    fun legacyNameOf(id: Uuid): String? = brands[id]?.legacyName

    @StringRes
    fun labelResOf(id: Uuid): Int? = brands[id]?.labelRes

    fun hasBrand(id: Uuid): Boolean = brands.containsKey(id)
}

/**
 * The provider name to render. Returns the stored `name` untouched unless it is
 * still the untouched built-in default, in which case the localized brand is used.
 *
 * Call this instead of `provider.name` anywhere the name is *displayed* (including
 * `AutoAIIcon`, which matches provider names to icons and must receive the same
 * string the user sees). Do NOT use it in edit fields, persistence, or API calls.
 */
@Composable
fun ProviderSetting.displayName(): String {
    val legacyName = BuiltInProviderBrands.legacyNameOf(id) ?: return name
    if (name != legacyName) return name
    val labelRes = BuiltInProviderBrands.labelResOf(id) ?: return name
    return stringResource(labelRes)
}

/**
 * Localized brand label per provider id, for the current locale.
 *
 * The provider list filter runs inside a `remember` block, which is not a
 * @Composable scope, so it cannot call [displayName]. This resolves the same strings
 * through the (locale-configured) context instead, so search matches what the user
 * actually sees -- e.g. typing "moonshot" finds 月之暗面 on an English device.
 */
@Composable
fun localizedBrandLabels(ids: Collection<Uuid>): Map<Uuid, String> {
    val context = LocalContext.current
    return remember(ids, context) {
        ids.mapNotNull { id ->
            BuiltInProviderBrands.labelResOf(id)?.let { res -> id to context.getString(res) }
        }.toMap()
    }
}
