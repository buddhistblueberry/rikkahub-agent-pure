package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the T-10 / (9) headless refusal policy:
 *
 *  - every tool the user reserved for a per-call confirmation (`NO_ALWAYS_ALLOW`) is refused,
 *  - the microphone group is refused too (nobody present to consent),
 *  - an ordinary tool is never refused — the policy only ever subtracts two named groups,
 *  - a conversation WITH an approval channel is untouched,
 *  - the refusal is a deterministic envelope, so it can never be retried.
 *
 * The wiring (`GenerationLoop` / `WorkflowEngine` calling this) is not reachable from a JVM
 * unit test — `GenerationLoop` needs Android and `WorkflowEngine` needs a repository — so what
 * is pinned here is the decision table both call sites read. CI covers the call sites.
 */
class HeadlessToolApprovalPolicyTest {

    // ---------------------------------------------------------------- refusals

    @Test
    fun `every NO_ALWAYS_ALLOW tool is refused`() {
        assertTrue(
            "the upstream set must not be empty, or this test proves nothing",
            ToolApprovalDefaults.NO_ALWAYS_ALLOW.isNotEmpty(),
        )
        ToolApprovalDefaults.NO_ALWAYS_ALLOW.forEach { name ->
            assertEquals(
                "$name must be refused in a headless run",
                true,
                HeadlessToolApprovalPolicy.isRefused(name),
            )
        }
    }

    @Test
    fun `the named privilege escalation surfaces are refused`() {
        // The card's examples, spelled out so a reshuffle of NO_ALWAYS_ALLOW cannot quietly
        // drop one without this test going red.
        listOf(
            "mcp_add",
            "mcp_update",
            "eval_javascript",
            "skill_install_from_url",
            "skill_install_from_text",
            "browser_eval_js",
            "keystore_generate_key",
            "keystore_decrypt",
            "nfc_write_tag",
            "grant_directory_access",
        ).forEach { name ->
            assertTrue("$name must be refused", HeadlessToolApprovalPolicy.isRefused(name))
        }
    }

    @Test
    fun `the microphone group is refused with nobody present to consent`() {
        assertTrue(HeadlessToolApprovalPolicy.isRefused("record_audio"))
        assertTrue(HeadlessToolApprovalPolicy.isRefused("speech_to_text"))
    }

    @Test
    fun `the refused set is exactly the union of the two groups`() {
        assertEquals(
            ToolApprovalDefaults.NO_ALWAYS_ALLOW + HeadlessToolApprovalPolicy.PRIVACY_SENSITIVE_TOOL_NAMES,
            HeadlessToolApprovalPolicy.REFUSED_TOOL_NAMES,
        )
        HeadlessToolApprovalPolicy.REFUSED_TOOL_NAMES.forEach { name ->
            assertNotNull("$name is in the set but has no detail", HeadlessToolApprovalPolicy.refusalDetail(name))
        }
    }

    // ------------------------------------------------------------- non-refusals

    @Test
    fun `ordinary tools are never refused`() {
        // Everything a cron job or a workflow legitimately does during the night. A false
        // positive here breaks a working schedule, which is why the policy is a closed set
        // rather than a heuristic.
        listOf(
            "read_file", "list_files", "write_text_file", "workspace_shell", "web_fetch",
            "web_extract", "search_web", "termux_run_command", "ssh_exec", "shizuku_exec",
            "get_battery_status", "get_location", "take_photo", "take_screenshot", "share",
            "open_file", "launch_app", "post_notification", "telegram_send_message",
            "subagent_dispatch", "ask_user", "mcp__github__get_file_contents",
            "keystore_encrypt", "keystore_verify", "keystore_list_keys",
        ).forEach { name ->
            assertFalse("$name must stay runnable headless", HeadlessToolApprovalPolicy.isRefused(name))
            assertNull("$name must produce no envelope", HeadlessToolApprovalPolicy.refusalEnvelope(name))
        }
    }

    @Test
    fun `a conversation with an approval channel is never refused`() {
        // The "non-headless behaviour is unchanged" property, as a test rather than a claim:
        // the flag is the caller's, and the policy is an identity function on it.
        HeadlessToolApprovalPolicy.REFUSED_TOOL_NAMES.forEach { name ->
            assertNull(
                "$name must not be pre-refused for a foreground conversation",
                HeadlessToolApprovalPolicy.refusalEnvelopeFor(name, headless = false),
            )
        }
        // ...and the same call with the flag on does produce one, so the null above is caused
        // by the flag and not by an empty policy.
        assertNotNull(
            HeadlessToolApprovalPolicy.refusalEnvelopeFor("eval_javascript", headless = true),
        )
    }

    @Test
    fun `the false branch of the flag is what suppresses the envelope`() {
        assertNull(HeadlessToolApprovalPolicy.refusalEnvelopeFor("mcp_add", false))
        assertNotNull(HeadlessToolApprovalPolicy.refusalEnvelopeFor("mcp_add", true))
    }

    // ------------------------------------------------------------------ envelope

    @Test
    fun `the envelope is JSON carrying the code, the tool and a detail`() {
        val raw = HeadlessToolApprovalPolicy.refusalEnvelope("eval_javascript")
        assertNotNull(raw)
        val obj = Json.parseToJsonElement(raw!!).jsonObject
        assertEquals(HeadlessToolApprovalPolicy.ERROR_CODE, obj["error"]?.jsonPrimitive?.contentOrNull)
        assertEquals("eval_javascript", obj["tool"]?.jsonPrimitive?.contentOrNull)
        val detail = obj["detail"]?.jsonPrimitive?.contentOrNull
        assertNotNull(detail)
        assertTrue("the detail should name the tool", detail!!.contains("eval_javascript"))
    }

    @Test
    fun `the error code matches the vocabulary the sub-agent surface uses`() {
        // T-09 rejects at the surface, T-10 rejects at execution; a model should read the same
        // code from both, so the string is pinned rather than free to drift. (The T-09 side is
        // `SubAgentToolSurface.denialReason`, which returns this same literal for a
        // NO_ALWAYS_ALLOW tool — verified by reading it 2026-10-01, not restated here because
        // that file pulls in the whole `ai` module and would drag Android into this JVM test.)
        assertEquals("tool_not_authorized", HeadlessToolApprovalPolicy.ERROR_CODE)
    }

    @Test
    fun `a refusal envelope is never classified as a transient failure`() {
        // ToolExecutionRetryPolicy retries transient envelopes. A refusal is deterministic —
        // retrying it would spend attempts on a decision that cannot change.
        HeadlessToolApprovalPolicy.REFUSED_TOOL_NAMES.forEach { name ->
            val raw = HeadlessToolApprovalPolicy.refusalEnvelope(name)!!
            assertFalse(
                "the $name refusal must not look retryable",
                ToolExecutionRetryPolicy.isTransientToolOutput(listOf(UIMessagePart.Text(raw))),
            )
        }
    }

    // -------------------------------------------------------------- edge inputs

    @Test
    fun `the tool name is trimmed and a blank name is not a refusal`() {
        assertTrue(HeadlessToolApprovalPolicy.isRefused("  eval_javascript  "))
        assertEquals(
            "eval_javascript",
            Json.parseToJsonElement(HeadlessToolApprovalPolicy.refusalEnvelope(" eval_javascript ")!!)
                .jsonObject["tool"]?.jsonPrimitive?.contentOrNull,
        )
        assertFalse(HeadlessToolApprovalPolicy.isRefused(""))
        assertFalse(HeadlessToolApprovalPolicy.isRefused("   "))
        assertNull(HeadlessToolApprovalPolicy.refusalEnvelope(""))
    }

    @Test
    fun `a prefix of a refused name is not refused`() {
        // Membership, not prefix matching: a future `eval_javascript_v2` must be added to the
        // set on purpose rather than inheriting a refusal from its name.
        assertFalse(HeadlessToolApprovalPolicy.isRefused("eval_javascript_v2"))
        assertFalse(HeadlessToolApprovalPolicy.isRefused("mcp_add_server"))
        assertFalse(HeadlessToolApprovalPolicy.isRefused("record_audio_file"))
    }

    @Test
    fun `the two groups read differently because the operator fix differs`() {
        val confirmation = HeadlessToolApprovalPolicy.refusalDetail("eval_javascript")!!
        val privacy = HeadlessToolApprovalPolicy.refusalDetail("record_audio")!!
        assertTrue(confirmation.contains("confirm"))
        assertTrue(privacy.contains("consent"))
        assertNotEquals(confirmation, privacy)
    }
}
