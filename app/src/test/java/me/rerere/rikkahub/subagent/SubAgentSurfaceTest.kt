package me.rerere.rikkahub.subagent

import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.tools.LocalToolOption
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.utils.JsonInstant
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
 * P2-04 — a sub-agent's own tool surface.
 *
 * Two contracts are load-bearing here:
 *
 *  - [SubAgentSurface.resolveChildAssistant] returns `null` (="inherit the parent verbatim") for
 *    every profile that does not define a surface, so the pre-P2-04 behaviour is preserved for
 *    each existing profile — the "model + system prompt" specialists users already have; and
 *  - [SubAgentSurface.apply] still applies the headless FLOOR per tool name to whatever surface the
 *    run ends up with, so "child ⊆ parent" is replaced by "child has its own surface, minus the
 *    tools nobody may run unattended" rather than by "child has anything it likes".
 */
class SubAgentSurfaceTest {

    private val parentId = Uuid.parse("00000000-0000-0000-0000-0000000000aa")
    private val workspaceId = Uuid.parse("00000000-0000-0000-0000-0000000000bb")
    private val mcpA = Uuid.parse("00000000-0000-0000-0000-0000000000c1")
    private val mcpB = Uuid.parse("00000000-0000-0000-0000-0000000000c2")

    private fun parent() = Assistant(
        id = parentId,
        name = "parent",
        localTools = listOf(LocalToolOption.TimeInfo, LocalToolOption.Workflows),
        disabledLocalTools = setOf("read_sensor"),
        mcpServers = setOf(mcpA),
        enableWebSearch = true,
        workspaceId = workspaceId,
        enabledSkills = setOf("skill-x"),
        enableSubAgentToolSurface = true,
    )

    private fun profile(
        localTools: List<LocalToolOption>? = null,
        disabledLocalTools: Set<String>? = null,
        mcpServers: Set<Uuid>? = null,
    ) = SubAgentProfile(
        name = "specialist",
        localTools = localTools,
        disabledLocalTools = disabledLocalTools,
        mcpServers = mcpServers,
    )

    private fun tool(name: String): Tool = Tool(name = name, description = "") { emptyList() }

    private fun namesOf(tools: List<Tool>): List<String> = tools.map { it.name }

    @After
    fun tearDown() {
        // The registry is process-global; leaving records behind would leak between tests.
        SubAgentSurface.clearAll()
    }

    // ---- resolveChildAssistant --------------------------------------------------------

    @Test
    fun `no profile means inherit`() {
        assertNull(SubAgentSurface.resolveChildAssistant(parent(), null))
    }

    @Test
    fun `no parent assistant means inherit`() {
        assertNull(SubAgentSurface.resolveChildAssistant(null, profile(localTools = listOf(LocalToolOption.TimeInfo))))
    }

    @Test
    fun `a profile that only picks a model and prompt has no surface of its own`() {
        val plain = SubAgentProfile(name = "plain", description = "d", systemPrompt = "p", modelId = Uuid.random())
        assertFalse(SubAgentSurface.hasOwnSurface(plain))
        assertNull(SubAgentSurface.resolveChildAssistant(parent(), plain))
    }

    @Test
    fun `an own local tool list replaces the parent's and leaves every other field inherited`() {
        val parent = parent()
        val child = SubAgentSurface.resolveChildAssistant(
            parent,
            profile(localTools = listOf(LocalToolOption.SubAgents)),
        )
        assertNotNull(child)
        child!!
        // The one thing the profile had an opinion about.
        assertEquals(listOf(LocalToolOption.SubAgents), child.localTools)
        // Everything the profile did not mention still comes from the parent.
        assertEquals(parent.disabledLocalTools, child.disabledLocalTools)
        assertEquals(parent.mcpServers, child.mcpServers)
        // The child is the SAME assistant for everything except the tool face.
        assertEquals(parent.id, child.id)
        assertEquals(parent.name, child.name)
        assertEquals(parent.enableWebSearch, child.enableWebSearch)
        assertEquals(parent.workspaceId, child.workspaceId)
        assertEquals(parent.enabledSkills, child.enabledSkills)
        assertEquals(parent.enableSubAgentToolSurface, child.enableSubAgentToolSurface)
    }

    @Test
    fun `an explicit empty local tool list is an opinion, not an inheritance`() {
        // emptyList() != null: the profile asked for a (useless but deliberate) empty surface.
        val child = SubAgentSurface.resolveChildAssistant(parent(), profile(localTools = emptyList()))
        assertNotNull(child)
        assertTrue(child!!.localTools.isEmpty())
    }

    @Test
    fun `a per-tool opt-out alone is enough to give the child its own surface`() {
        val parent = parent()
        val child = SubAgentSurface.resolveChildAssistant(parent, profile(disabledLocalTools = setOf("show_toast")))
        assertNotNull(child)
        assertEquals(setOf("show_toast"), child!!.disabledLocalTools)
        assertEquals(parent.localTools, child.localTools)
        assertEquals(parent.mcpServers, child.mcpServers)
    }

    @Test
    fun `an mcp server choice alone is enough to give the child its own surface`() {
        val parent = parent()
        val child = SubAgentSurface.resolveChildAssistant(parent, profile(mcpServers = setOf(mcpA, mcpB)))
        assertNotNull(child)
        assertEquals(setOf(mcpA, mcpB), child!!.mcpServers)
        assertEquals(parent.localTools, child.localTools)
        assertEquals(parent.disabledLocalTools, child.disabledLocalTools)
    }

    @Test
    fun `all three fields together are taken from the profile`() {
        val child = SubAgentSurface.resolveChildAssistant(
            parent(),
            profile(
                localTools = listOf(LocalToolOption.Workflows),
                disabledLocalTools = setOf("read_sensor", "show_toast"),
                mcpServers = setOf(mcpB),
            ),
        )
        assertNotNull(child)
        assertEquals(listOf(LocalToolOption.Workflows), child!!.localTools)
        assertEquals(setOf("read_sensor", "show_toast"), child.disabledLocalTools)
        assertEquals(setOf(mcpB), child.mcpServers)
    }

    @Test
    fun `the child can carry a tool group the parent does not have`() {
        // The whole point of P2-04: a specialist is not limited to the parent's surface.
        val parent = parent().copy(localTools = listOf(LocalToolOption.TimeInfo))
        val child = SubAgentSurface.resolveChildAssistant(parent, profile(localTools = listOf(LocalToolOption.SubAgents)))
        assertEquals(listOf(LocalToolOption.SubAgents), child!!.localTools)
        assertFalse(child.localTools.containsAll(parent.localTools))
    }

    @Test
    fun `hasOwnSurface is true for each field and false for none`() {
        assertFalse(SubAgentSurface.hasOwnSurface(null))
        assertFalse(SubAgentSurface.hasOwnSurface(SubAgentProfile(name = "a")))
        assertTrue(SubAgentSurface.hasOwnSurface(profile(localTools = emptyList())))
        assertTrue(SubAgentSurface.hasOwnSurface(profile(disabledLocalTools = emptySet())))
        assertTrue(SubAgentSurface.hasOwnSurface(profile(mcpServers = emptySet())))
    }

    // ---- freeze registry --------------------------------------------------------------

    @Test
    fun `apply returns the same instance when the conversation was never frozen`() {
        val tools = listOf(tool("search_web"), tool("subagent_dispatch"), tool("take_photo"))
        val frozen = Uuid.random()
        SubAgentSurface.freeze(frozen)
        assertSame(tools, SubAgentSurface.apply(Uuid.random(), tools))
        assertNull(SubAgentSurface.frozenFor(Uuid.random()))
        assertNotNull(SubAgentSurface.frozenFor(frozen))
        assertNull(SubAgentSurface.assistantFor(frozen))
    }

    @Test
    fun `a frozen conversation loses the denied tools and keeps the rest`() {
        val conversation = Uuid.random()
        SubAgentSurface.freeze(conversation)
        val tools = listOf(
            tool("search_web"),
            tool("subagent_dispatch"),
            tool("ask_user"),
            tool("eval_javascript"),
            tool("take_photo"),
            tool("mcp__github__get_me"),
        )
        val filtered = SubAgentSurface.apply(conversation, tools)
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
        SubAgentSurface.freeze(conversation, requested = listOf("search_web"))
        val tools = listOf(tool("search_web"))
        assertSame(tools, SubAgentSurface.apply(conversation, tools))
    }

    @Test
    fun `a requested allow list narrows the frozen surface`() {
        val conversation = Uuid.random()
        SubAgentSurface.freeze(conversation, requested = setOf("search_web", "web_fetch"))
        val tools = listOf(tool("search_web"), tool("web_fetch"), tool("workspace_shell"), tool("memory_tool"))
        assertEquals(listOf("search_web", "web_fetch"), namesOf(SubAgentSurface.apply(conversation, tools)))
    }

    @Test
    fun `requested names that do not exist in the surface are simply dropped`() {
        val conversation = Uuid.random()
        SubAgentSurface.freeze(conversation, requested = setOf("search_web", "no_such_tool"))
        val tools = listOf(tool("search_web"), tool("memory_tool"))
        assertEquals(listOf("search_web"), namesOf(SubAgentSurface.apply(conversation, tools)))
        assertEquals(setOf("search_web", "no_such_tool"), SubAgentSurface.frozenFor(conversation)?.requested)
    }

    @Test
    fun `an empty or blank requested list means no narrowing rather than no tools`() {
        val empty = Uuid.random()
        assertNull(SubAgentSurface.freeze(empty, requested = emptyList()).requested)
        val blank = Uuid.random()
        assertNull(SubAgentSurface.freeze(blank, requested = listOf("", "   ")).requested)
        val tools = listOf(tool("search_web"), tool("memory_tool"), tool("take_photo"))
        assertEquals(listOf("search_web", "memory_tool"), namesOf(SubAgentSurface.apply(empty, tools)))
        assertEquals(listOf("search_web", "memory_tool"), namesOf(SubAgentSurface.apply(blank, tools)))
    }

    @Test
    fun `a denial beats a requested allow list`() {
        val conversation = Uuid.random()
        // Even if a dispatcher asks for it explicitly, a denied tool must not be handed over.
        SubAgentSurface.freeze(conversation, requested = setOf("search_web", "eval_javascript"))
        val tools = listOf(tool("search_web"), tool("eval_javascript"))
        assertEquals(listOf("search_web"), namesOf(SubAgentSurface.apply(conversation, tools)))
    }

    @Test
    fun `the floor strips a denied tool the child's own surface asked for`() {
        // P2-04's core safety claim: a profile that enables an approval-required group gets a
        // surface, but the floor still removes every tool nobody may run unattended.
        val conversation = Uuid.random()
        val child = SubAgentSurface.resolveChildAssistant(
            parent(),
            profile(localTools = listOf(LocalToolOption.TimeInfo, LocalToolOption.Battery)),
        )
        SubAgentSurface.freeze(conversation, assistant = child)
        val tools = listOf(tool("get_battery_status"), tool("eval_javascript"), tool("record_audio"), tool("ask_user"))
        assertEquals(listOf("get_battery_status"), namesOf(SubAgentSurface.apply(conversation, tools)))
    }

    @Test
    fun `release restores the identity behaviour`() {
        val conversation = Uuid.random()
        val tools = listOf(tool("search_web"), tool("subagent_dispatch"))
        SubAgentSurface.freeze(conversation, assistant = parent())
        assertEquals(listOf("search_web"), namesOf(SubAgentSurface.apply(conversation, tools)))
        SubAgentSurface.release(conversation)
        assertNull(SubAgentSurface.frozenFor(conversation))
        assertNull(SubAgentSurface.assistantFor(conversation))
        assertSame(tools, SubAgentSurface.apply(conversation, tools))
    }

    @Test
    fun `assistantFor hands back exactly the frozen surface assistant`() {
        val conversation = Uuid.random()
        val child = SubAgentSurface.resolveChildAssistant(parent(), profile(mcpServers = setOf(mcpB)))
        assertNotNull(child)
        SubAgentSurface.freeze(conversation, assistant = child)
        assertSame(child, SubAgentSurface.assistantFor(conversation))
        assertSame(child, SubAgentSurface.frozenFor(conversation)?.assistant)
    }

    @Test
    fun `freezes are per conversation and free of cross-talk`() {
        val narrowed = Uuid.random()
        val widened = Uuid.random()
        SubAgentSurface.freeze(narrowed, requested = listOf("search_web"))
        SubAgentSurface.freeze(widened, assistant = parent())
        val tools = listOf(tool("search_web"), tool("memory_tool"), tool("ask_user"))

        assertEquals(listOf("search_web"), namesOf(SubAgentSurface.apply(narrowed, tools)))
        assertEquals(listOf("search_web", "memory_tool"), namesOf(SubAgentSurface.apply(widened, tools)))
        assertSame(tools, SubAgentSurface.apply(Uuid.random(), tools))
        assertNotNull(SubAgentSurface.assistantFor(widened))
        assertNull(SubAgentSurface.assistantFor(narrowed))
    }

    @Test
    fun `clearAll unbinds every conversation`() {
        val conversation = Uuid.random()
        val tools = listOf(tool("search_web"), tool("ask_user"))
        SubAgentSurface.freeze(conversation, assistant = parent())
        assertEquals(1, SubAgentSurface.apply(conversation, tools).size)
        SubAgentSurface.clearAll()
        assertSame(tools, SubAgentSurface.apply(conversation, tools))
        assertNull(SubAgentSurface.assistantFor(conversation))
    }

    @Test
    fun `freezing the same conversation twice replaces the record`() {
        val conversation = Uuid.random()
        SubAgentSurface.freeze(conversation, assistant = parent(), requested = listOf("search_web"))
        assertNotNull(SubAgentSurface.assistantFor(conversation))
        SubAgentSurface.freeze(conversation)
        assertNull(SubAgentSurface.frozenFor(conversation)?.requested)
        assertNull(SubAgentSurface.assistantFor(conversation))
    }

    // ---- persistence ------------------------------------------------------------------

    @Test
    fun `a profile with no surface does not grow the persisted json`() {
        val encoded = JsonInstant.encodeToString(
            SubAgentProfile.serializer(),
            SubAgentProfile(id = Uuid.parse("00000000-0000-0000-0000-000000000001"), name = "a"),
        )
        // @EncodeDefault(NEVER): the three new fields must be absent while unset, so an existing
        // settings blob re-encodes byte-for-byte identically.
        assertFalse(encoded, encoded.contains("localTools"))
        assertFalse(encoded, encoded.contains("disabledLocalTools"))
        assertFalse(encoded, encoded.contains("mcpServers"))
    }

    @Test
    fun `the three surface fields survive a json round trip`() {
        val original = SubAgentProfile(
            id = Uuid.parse("00000000-0000-0000-0000-000000000002"),
            name = "specialist",
            localTools = listOf(LocalToolOption.TimeInfo, LocalToolOption.SubAgents),
            disabledLocalTools = setOf("show_toast"),
            mcpServers = setOf(mcpA, mcpB),
        )
        val restored = JsonInstant.decodeFromString(
            SubAgentProfile.serializer(),
            JsonInstant.encodeToString(SubAgentProfile.serializer(), original),
        )
        assertEquals(original, restored)
    }

    @Test
    fun `a pre-p2-04 profile decodes with every surface field unset`() {
        // Exactly the shape the store wrote before this card (new fields absent).
        val legacy = """
            {"id":"00000000-0000-0000-0000-000000000003","name":"legacy","description":"",
             "systemPrompt":"be nice","modelId":null,"enabled":true}
        """.trimIndent()
        val decoded = JsonInstant.decodeFromString(SubAgentProfile.serializer(), legacy)
        assertEquals("legacy", decoded.name)
        assertNull(decoded.localTools)
        assertNull(decoded.disabledLocalTools)
        assertNull(decoded.mcpServers)
        // …and it inherits, i.e. the pre-P2-04 behaviour.
        assertNull(SubAgentSurface.resolveChildAssistant(parent(), decoded))
    }
}
