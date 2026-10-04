package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.ImageAspectRatio
import me.rerere.ai.util.json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * DashScope (Aliyun Bailian) image generation, on the provider's **native** async task API.
 *
 * Why this file exists: DashScope is configured as a plain OpenAI-compatible provider
 * (`https://dashscope.aliyuncs.com/compatible-mode/v1`), and that base URL has **no**
 * `/images/generations` endpoint — it answers `404`, while `/chat/completions` on the same
 * host answers `401` (i.e. the route exists). So the generic OpenAI image path can never work
 * there; Qwen-Image / Wanx must go through `text2image/image-synthesis`, which is asynchronous:
 * submit returns a `task_id`, and the caller polls `GET /api/v1/tasks/{id}` until the task
 * reports `SUCCEEDED`, then downloads the result URLs.
 *
 * Everything that can be decided without a socket lives here (root derivation, size mapping,
 * request body, response parsing) so a bare-JVM unit test can pin it, mirroring the split
 * used for the OpenRouter Images API in [OpenRouterRequestBuilder].
 */

/**
 * DashScope is reachable on two hosts — `dashscope.aliyuncs.com` (China) and
 * `dashscope-intl.aliyuncs.com` (International) — so matching a literal host string, or a
 * suffix like `dashscope.aliyuncs.com`, misses the `-intl` one. Match the first label instead,
 * which also rejects a typo-squat such as `notdashscope.aliyuncs.com`.
 */
private const val ALIYUNS_HOST_SUFFIX = ".aliyuncs.com"

/** The host of [baseUrl] when it is a DashScope host, else `null`. */
private fun dashScopeHost(baseUrl: String): String? {
    val host = baseUrl.toHttpUrlOrNull()?.host?.lowercase() ?: return null
    if (!host.endsWith(ALIYUNS_HOST_SUFFIX)) return null
    val firstLabel = host.substringBefore('.')
    return if (firstLabel == "dashscope" || firstLabel.startsWith("dashscope-")) host else null
}

/** `POST {root}/api/v1/services/aigc/text2image/image-synthesis` (with `X-DashScope-Async: enable`). */
const val DASHSCOPE_IMAGE_SYNTHESIS_PATH = "/api/v1/services/aigc/text2image/image-synthesis"

/** Poll attempts before giving up (≈ 2 minutes at the interval below). */
const val DASHSCOPE_POLL_ATTEMPTS = 24

/** Delay between two `GET /api/v1/tasks/{id}` polls. */
const val DASHSCOPE_POLL_INTERVAL_MS = 5_000L

/** True when [baseUrl] points at a DashScope host (China or International). */
fun isDashScopeBaseUrl(baseUrl: String): Boolean = dashScopeHost(baseUrl) != null

/**
 * The DashScope *native* API root for an OpenAI-compatible [baseUrl] — scheme + host (+ non-default
 * port), with any `/compatible-mode/v1` path dropped:
 *
 * ```
 * https://dashscope.aliyuncs.com/compatible-mode/v1   ->   https://dashscope.aliyuncs.com
 * ```
 *
 * Returns `null` when [baseUrl] is not a DashScope host, so the caller can fall through to the
 * generic path instead of building a nonsense URL.
 */
fun dashScopeNativeRoot(baseUrl: String): String? {
    if (dashScopeHost(baseUrl) == null) return null
    val url = baseUrl.toHttpUrlOrNull() ?: return null
    return url.newBuilder()
        .encodedPath("/")
        .query(null)
        .fragment(null)
        .build()
        .toString()
        .trimEnd('/')
}

/** `GET {root}/api/v1/tasks/{taskId}`. */
fun dashScopeTaskPath(taskId: String): String = "/api/v1/tasks/$taskId"

/**
 * Qwen-Image's native sizes, in DashScope's `WIDTH*HEIGHT` notation (note the `*`, not `x`).
 *
 * Qwen-Image accepts these three; a caller that wants a Wanx-only size can still override it via
 * the model's custom body (`parameters.size`), which is merged last.
 */
fun dashScopeImageSize(aspectRatio: ImageAspectRatio): String = when (aspectRatio) {
    ImageAspectRatio.SQUARE -> "1328*1328"
    ImageAspectRatio.LANDSCAPE -> "1664*928"
    ImageAspectRatio.PORTRAIT -> "928*1664"
}

/** The `image-synthesis` submit body: `{ model, input:{prompt}, parameters:{size, n} }`. */
fun buildDashScopeImageRequestBody(
    model: Model,
    prompt: String,
    aspectRatio: ImageAspectRatio,
    numOfImages: Int,
): JsonObject = buildJsonObject {
    put("model", model.modelId)
    putJsonObject("input") {
        put("prompt", prompt)
    }
    putJsonObject("parameters") {
        put("size", dashScopeImageSize(aspectRatio))
        put("n", numOfImages)
    }
}

/** `output.task_id` of the submit response, or `null` when absent/unparseable. */
fun parseDashScopeTaskId(body: String): String? = dashScopeOutput(body)
    ?.get("task_id")
    ?.jsonPrimitive
    ?.contentOrNull

/** `output.task_status` (`PENDING` / `RUNNING` / `SUCCEEDED` / `FAILED` / `CANCELED` / `UNKNOWN`). */
fun parseDashScopeTaskStatus(body: String): String? = dashScopeOutput(body)
    ?.get("task_status")
    ?.jsonPrimitive
    ?.contentOrNull

/** `output.message` — the human-readable reason a task failed, when the provider supplies one. */
fun parseDashScopeTaskMessage(body: String): String? = dashScopeOutput(body)
    ?.get("message")
    ?.jsonPrimitive
    ?.contentOrNull

/** The result image URLs of a `SUCCEEDED` task; empty when the body has none. */
fun parseDashScopeImageUrls(body: String): List<String> = dashScopeOutput(body)
    ?.get("results")
    ?.jsonArray
    ?.mapNotNull { it.jsonObject["url"]?.jsonPrimitive?.contentOrNull }
    .orEmpty()

/** `output` of a DashScope envelope, or `null` for any malformed body (never throws). */
private fun dashScopeOutput(body: String): JsonObject? = runCatching {
    json.parseToJsonElement(body).jsonObject["output"]?.jsonObject
}.getOrNull()
