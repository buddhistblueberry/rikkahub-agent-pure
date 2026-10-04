package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.ImageAspectRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure decisions behind the DashScope image path. The live contract these mirror was
 * confirmed against the real endpoint: `POST .../text2image/image-synthesis` with
 * `X-DashScope-Async: enable` returns `{output:{task_id, task_status:"PENDING"}}`, and
 * `GET /api/v1/tasks/{id}` eventually returns `{output:{task_status:"SUCCEEDED",
 * results:[{url:...}]}}`.
 *
 * Lives under `app/src/test` (not `ai/src/test`) on purpose: CI runs only
 * `:app:testDebugUnitTest`, so this is the only place the gate actually executes it — the same
 * reason `OpenAIMultimodalPartsTest` sits here.
 */
class DashScopeImageRequestTest {

    // ---- host detection ----

    @Test
    fun dashscope_compatible_mode_url_is_recognised() {
        assertTrue(isDashScopeBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1"))
        assertTrue(isDashScopeBaseUrl("https://dashscope-intl.aliyuncs.com/compatible-mode/v1"))
    }

    @Test
    fun other_providers_are_not_dashscope() {
        assertFalse(isDashScopeBaseUrl("https://api.openai.com/v1"))
        assertFalse(isDashScopeBaseUrl("https://openrouter.ai/api/v1"))
        // Lookalike hosts must not match: a different domain that merely ends in the suffix,
        // and a typo-squat whose first label is not "dashscope".
        assertFalse(isDashScopeBaseUrl("https://notdashscope.aliyuncs.com.evil.test/v1"))
        assertFalse(isDashScopeBaseUrl("https://notdashscope.aliyuncs.com/v1"))
        assertFalse(isDashScopeBaseUrl("not a url"))
    }

    // ---- native root derivation ----

    @Test
    fun native_root_drops_the_compatible_mode_path() {
        assertEquals(
            "https://dashscope.aliyuncs.com",
            dashScopeNativeRoot("https://dashscope.aliyuncs.com/compatible-mode/v1"),
        )
    }

    @Test
    fun native_root_keeps_a_non_default_port_and_scheme() {
        assertEquals(
            "http://dashscope.aliyuncs.com:8443",
            dashScopeNativeRoot("http://dashscope.aliyuncs.com:8443/compatible-mode/v1"),
        )
    }

    @Test
    fun native_root_is_null_for_non_dashscope_hosts() {
        assertNull(dashScopeNativeRoot("https://api.openai.com/v1"))
        assertNull(dashScopeNativeRoot("garbage"))
    }

    // ---- sizes and request body ----

    @Test
    fun size_uses_dashscope_star_notation() {
        assertEquals("1328*1328", dashScopeImageSize(ImageAspectRatio.SQUARE))
        assertEquals("1664*928", dashScopeImageSize(ImageAspectRatio.LANDSCAPE))
        assertEquals("928*1664", dashScopeImageSize(ImageAspectRatio.PORTRAIT))
    }

    @Test
    fun submit_body_has_model_input_and_parameters() {
        val model = Model(modelId = "qwen-image-3.0")
        val body = buildDashScopeImageRequestBody(
            model = model,
            prompt = "a red panda, watercolour",
            aspectRatio = ImageAspectRatio.LANDSCAPE,
            numOfImages = 2,
        )
        assertEquals("qwen-image-3.0", body["model"]!!.jsonPrimitive.content)
        assertEquals("a red panda, watercolour", body["input"]!!.jsonObject["prompt"]!!.jsonPrimitive.content)
        val params = body["parameters"]!!.jsonObject
        assertEquals("1664*928", params["size"]!!.jsonPrimitive.content)
        assertEquals("2", params["n"]!!.jsonPrimitive.content)
    }

    // ---- response parsing ----

    @Test
    fun parses_task_id_and_status_from_submit_response() {
        val body = """{"request_id":"r1","output":{"task_id":"abc-123","task_status":"PENDING"}}"""
        assertEquals("abc-123", parseDashScopeTaskId(body))
        assertEquals("PENDING", parseDashScopeTaskStatus(body))
    }

    @Test
    fun parses_every_result_url_from_a_succeeded_task() {
        val body = """
            {"output":{"task_id":"abc","task_status":"SUCCEEDED","results":[
                {"url":"https://dashscope-result.oss-cn.aliyuncs.com/1.png"},
                {"url":"https://dashscope-result.oss-cn.aliyuncs.com/2.png"}
            ]}}
        """.trimIndent()
        assertEquals(
            listOf(
                "https://dashscope-result.oss-cn.aliyuncs.com/1.png",
                "https://dashscope-result.oss-cn.aliyuncs.com/2.png",
            ),
            parseDashScopeImageUrls(body),
        )
    }

    @Test
    fun parses_failure_message() {
        val body = """{"output":{"task_status":"FAILED","message":"DataInspectionFailed"}}"""
        assertEquals("FAILED", parseDashScopeTaskStatus(body))
        assertEquals("DataInspectionFailed", parseDashScopeTaskMessage(body))
    }

    @Test
    fun malformed_bodies_never_throw() {
        for (body in listOf("", "not json", "{}", """{"output":null}""", """{"output":{"results":[]}}""")) {
            assertNull(parseDashScopeTaskId(body))
            assertNull(parseDashScopeTaskStatus(body))
            assertEquals(emptyList<String>(), parseDashScopeImageUrls(body))
        }
    }
}
