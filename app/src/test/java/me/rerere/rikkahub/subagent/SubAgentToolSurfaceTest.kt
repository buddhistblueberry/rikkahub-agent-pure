package me.rerere.rikkahub.subagent

import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.tools.ToolApprovalDefaults
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/**
 * T-09 / (8) — the sub-agent tool-surface policy and the freeze registry.
 *
 * Two things are load-bearing here and both are asserted below:
 *
 *  - the policy removes exactly the tools a headless sub-agent must not receive (the
 *    `subagent_` handles, the per-call-approval tools, `ask_user`, the device-UI-bound tools,
 *    the privacy-sensitive capture tools) and nothing else, and
 *  - `apply` is the IDENTITY — same `List<Tool>` instance — for every conversation that was
 *    never frozen, which is what makes the feature byte-for-byte inert when the assistant's
 *    flag is off.
 */
class SubAgentToolSurfaceTest {

    private fun tool(name: String): Tool = Tool(name = name, description = "") { emptyList() }

    private fun namesOf(tools: List<Tool>): List<String> = tools.map { it.name }

    @After
    fun tearDown() {
        // The registry is process-global; leaving records behind would leak between tests.
        SubAgentToolSurface.clearAll()
    }

    // ---- policy ---------------------------------------------------------------------

    @Test
    fun `the subagent handles are denied by prefix`() {
        assertEquals("tool_unavailable_headless", SubAgentToolSurface.denialReason("subagent_dispatch"))
        assertEquals("tool_unavailable_headless", SubAgentToolSurface.denialReason("subagent_list"))
        assertEquals("tool_unavailable_headless", SubAgentToolSurface.denialReason("subagent_get"))
        assertEquals("tool_unavailable_headless", SubAgentToolSurface.denialReason("subagent_cancel"))
        // Prefix rule, not a fixed list — a future handle is covered without editing the file.
        assertEquals("tool_unavailable_headless", SubAgentToolSurface.denialReason("subagent_something_new"))
    }

    @Test
    fun `every NO_ALWAYS_ALLOW tool is denied`() {
        assertTrue(ToolApprovalDefaults.NO_ALWAYS_ALLOW.isNotEmpty())
        ToolApprovalDefaults.NO_ALWAYS_ALLOW.forEach { name ->
            assertTrue(name, SubAgentToolSurface.isDenied(name))
            // Two of them (nfc_write_tag / grant_directory_access) are also device-UI-bound, and
            // the UI reason wins because "it cannot run headless at all" is the more useful thing
            // to tell a dispatcher. Everything else reports the approval reason.
            val expected = if (name in SubAgentToolSurface.UI_BOUND_TOOL_NAMES) {
                "tool_unavailable_headless"
            } else {
                "tool_not_authorized"
            }
            assertEquals(name, expected, SubAgentToolSurface.denialReason(name))
        }
    }

    @Test
    fun `ask_user is denied so a headless run does not burn a trip on it`() {
        assertEquals("tool_unavailable_headless", SubAgentToolSurface.denialReason("ask_user"))
        assertFalse(SubAgentToolSurface.safeNames(listOf("ask_user")).contains("ask_user"))
    }

    @Test
    fun `device UI bound tools are denied`() {
        val expected = setOf(
            "take_photo",
            "verify_fingerprint",
            "nfc_read_tag",
            "nfc_write_tag",
            "grant_directory_access",
        )
        assertEquals(expected, SubAgentToolSurface.UI_BOUND_TOOL_NAMES)
        expected.forEach { name ->
            assertEquals("tool_unavailable_headless", SubAgentToolSurface.denialReason(name))
        }
    }

    @Test
    fun `privacy sensitive capture tools are denied`() {
        assertEquals(
            setOf("record_audio", "speech_to_text"),
            SubAgentToolSurface.PRIVACY_SENSITIVE_TOOL_NAMES,
        )
        // Ground truth: both sit in ALWAYS_ASK (per-call consent) in the non-headless world, and
        // a headless run is exactly the world that would skip that consent.
        SubAgentToolSurface.PRIVACY_SENSITIVE_TOOL_NAMES.forEach { name ->
            assertTrue(name, name in ToolApprovalDefaults.ALWAYS_ASK)
            assertEquals(name, "tool_not_authorized", SubAgentToolSurface.denialReason(name))
            assertFalse(SubAgentToolSurface.safeNames(listOf(name)).contains(name))
        }
    }

    @Test
    fun `ordinary tools survive the policy`() {
        val kept = listOf(
            "search_web",
            "web_fetch",
            "workspace_shell",
            "use_skill",
            "memory_tool",
            "mcp__github__get_me",
            "browser",
            "termux_run_command",
            "open_file",
            "show_toast",
        )
        assertEquals(kept.toSet(), SubAgentToolSurface.safeNames(kept))
        kept.forEach { assertNull(it, SubAgentToolSurface.denialReason(it)) }
    }

    @Test
    fun `safeNames is a subtraction and never invents names`() {
        val mixed = listOf("search_web", "subagent_dispatch", "ask_user", "eval_javascript", "take_photo")
        assertEquals(setOf("search_web"), SubAgentToolSurface.safeNames(mixed))
        assertTrue(SubAgentToolSurface.safeNames(emptyList()).isEmpty())
    }

    // ---- freeze registry -------------------------------------------------------------

    @Test
    fun `apply returns the same instance when the conversation was never frozen`() {
        val tools = listOf(tool("search_web"), tool("subagent_dispatch"), tool("take_photo"))
        val frozenConversation = Uuid.random()
        val other = Uuid.random()
        SubAgentToolSurface.freeze(frozenConversation)

        assertSame(tools, SubAgentToolSurface.apply(other, tools))
        assertNull(SubAgentToolSurface.frozenFor(other))
        assertNotNull(SubAgentToolSurface.frozenFor(frozenConversation))
    }

    @Test
    fun `a frozen conversation loses the denied tools and keeps the rest`() {
        val conversation = Uuid.random()
        SubAgentToolSurface.freeze(conversation)
        val tools = listOf(
            tool("search_web"),
            tool("subagent_dispatch"),
            tool("ask_user"),
            tool("eval_javascript"),
            tool("take_photo"),
            tool("mcp__github__get_me"),
        )
        val filtered = SubAgentToolSurface.apply(conversation, tools)
        assertEquals(listOf("search_web", "mcp__github__get_me"), namesOf(filtered))
        // The surviving tools are the very same instances, not copies.
        assertSame(tools[0], filtered[0])
        assertSame(tools[5], filtered[1])
        // The caller's list is untouched.
        assertEquals(6, tools.size)
    }

    @Test
    fun `a frozen conversation with nothing to remove still gets the original instance`() {
        val conversation = Uuid.random()
        SubAgentToolSurface.freeze(conversation, requested = listOf("search_web"))
        val tools = listOf(tool("search_web"))
        assertSame(tools, SubAgentToolSurface.apply(conversation, tools))
    }

    @Test
    fun `a requested allow list narrows the frozen surface`() {
        val conversation = Uuid.random()
        SubAgentToolSurface.freeze(conversation, requested = setOf("search_web", "web_fetch"))
        val tools = listOf(tool("search_web"), tool("web_fetch"), tool("workspace_shell"), tool("memory_tool"))
        assertEquals(listOf("search_web", "web_fetch"), namesOf(SubAgentToolSurface.apply(conversation, tools)))
    }

    @Test
    fun `requested names that do not exist in the surface are simply dropped`() {
        val conversation = Uuid.random()
        SubAgentToolSurface.freeze(conversation, requested = setOf("search_web", "no_such_tool"))
        val tools = listOf(tool("search_web"), tool("memory_tool"))
        assertEquals(listOf("search_web"), namesOf(SubAgentToolSurface.apply(conversation, tools)))
        val surface = SubAgentToolSurface.frozenFor(conversation)
        assertEquals(setOf("search_web", "no_such_tool"), surface?.requested)
    }

    @Test
    fun `an empty requested list means no narrowing rather than no tools`() {
        val conversation = Uuid.random()
        val surface = SubAgentToolSurface.freeze(conversation, requested = emptyList())
        assertNull(surface.requested)
        val tools = listOf(tool("search_web"), tool("memory_tool"), tool("take_photo"))
        assertEquals(listOf("search_web", "memory_tool"), namesOf(SubAgentToolSurface.apply(conversation, tools)))
    }

    @Test
    fun `a blank requested list means no narrowing`() {
        val conversation = Uuid.random()
        assertNull(SubAgentToolSurface.freeze(conversation, requested = listOf("", "   ")).requested)
        assertNull(SubAgentToolSurface.freeze(Uuid.random(), requested = null).requested)
    }

    @Test
    fun `a denial beats a requested allow list`() {
        val conversation = Uuid.random()
        // Even if a dispatcher asks for it explicitly, a denied tool must not be handed over.
        SubAgentToolSurface.freeze(conversation, requested = setOf("search_web", "eval_javascript"))
        val tools = listOf(tool("search_web"), tool("eval_javascript"))
        assertEquals(listOf("search_web"), namesOf(SubAgentToolSurface.apply(conversation, tools)))
    }

    @Test
    fun `release restores the identity behaviour`() {
        val conversation = Uuid.random()
        val tools = listOf(tool("search_web"), tool("subagent_dispatch"))
        SubAgentToolSurface.freeze(conversation)
        assertEquals(listOf("search_web"), namesOf(SubAgentToolSurface.apply(conversation, tools)))
        SubAgentToolSurface.release(conversation)
        assertNull(SubAgentToolSurface.frozenFor(conversation))
        assertSame(tools, SubAgentToolSurface.apply(conversation, tools))
    }

    @Test
    fun `freezes are per conversation`() {
        val narrowed = Uuid.random()
        val widened = Uuid.random()
        SubAgentToolSurface.freeze(narrowed, requested = listOf("search_web"))
        SubAgentToolSurface.freeze(widened)
        val tools = listOf(tool("search_web"), tool("memory_tool"), tool("ask_user"))

        assertEquals(listOf("search_web"), namesOf(SubAgentToolSurface.apply(narrowed, tools)))
        assertEquals(listOf("search_web", "memory_tool"), namesOf(SubAgentToolSurface.apply(widened, tools)))
        assertSame(tools, SubAgentToolSurface.apply(Uuid.random(), tools))
    }

    @Test
    fun `clearAll unbinds every conversation`() {
        val conversation = Uuid.random()
        val tools = listOf(tool("search_web"), tool("ask_user"))
        SubAgentToolSurface.freeze(conversation)
        assertEquals(1, SubAgentToolSurface.apply(conversation, tools).size)
        SubAgentToolSurface.clearAll()
        assertSame(tools, SubAgentToolSurface.apply(conversation, tools))
    }

    @Test
    fun `freezing the same conversation twice replaces the record`() {
        val conversation = Uuid.random()
        SubAgentToolSurface.freeze(conversation, requested = listOf("search_web"))
        SubAgentToolSurface.freeze(conversation)
        assertNull(SubAgentToolSurface.frozenFor(conversation)?.requested)
    }
}
