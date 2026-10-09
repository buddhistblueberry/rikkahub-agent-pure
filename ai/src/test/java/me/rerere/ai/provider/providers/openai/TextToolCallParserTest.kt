package me.rerere.ai.provider.providers.openai

import me.rerere.ai.core.InputSchema
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.util.json
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.stream.SseEvent
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for models that emit tool calls as literal text rather than as native
 * `tool_calls` deltas — seen with the MiniMax-based free tier behind Opencode Zen, which made
 * raw `<tool_call>` markup visible in the chat instead of executing the tool.
 */
class TextToolCallParserTest {

    private val echo = Tool(
        name = "agent-core",
        description = "core",
        parameters = {
            InputSchema.Obj(
                properties = JsonObject(mapOf("task" to JsonPrimitive("string"))),
                required = listOf("task"),
            )
        },
        execute = { emptyList() },
    )

    private val agent = Tool(
        name = "autonomous-agent",
        description = "agent",
        parameters = {
            InputSchema.Obj(
                properties = JsonObject(mapOf("goal" to JsonPrimitive("string"))),
                required = listOf("goal"),
            )
        },
        execute = { emptyList() },
    )

    /**
     * Feed the deltas then flush the tail, mirroring how the decoder drains the parser at
     * end of stream — the last few characters stay buffered in case they are a partial tag.
     */
    private fun parts(parser: TextToolCallParser, vararg deltas: String): List<UIMessagePart> {
        val out = deltas.flatMap { parser.feed(it) }.toMutableList()
        out += parser.flushPending()
        return out
    }

    @Test
    fun `parses a well formed text tool call`() {
        val parser = TextToolCallParser(listOf(echo))
        val out = parts(
            parser,
            "Let me check that. <tool_call>{\"name\":\"agent-core\",\"input\":{\"task\":\"battery\"}}</tool_call>"
        )
        val tool = out.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("agent-core", tool.toolName)
        // Parse the input rather than regex-slice it: Regex.find() yields the whole match,
        // so the old assertion compared '"task":"battery"' against 'battery' and could never pass.
        val input = json.parseToJsonElement(tool.input).jsonObject
        assertEquals("battery", input["task"]?.jsonPrimitive?.content)
        assertEquals("tool_calls", parser.consumePendingFinishReason())
        assertTrue(parser.detected)
    }

    @Test
    fun `survives a tag split across stream chunks`() {
        val parser = TextToolCallParser(listOf(echo))
        val out = parts(
            parser,
            "<tool_", "call>{\"name\":\"agent-core\",", "\"input\":{\"task\":\"x\"}}</tool_", "call>"
        )
        val tool = out.filterIsInstance<UIMessagePart.Tool>().single()
        assertEquals("agent-core", tool.toolName)
    }

    @Test
    fun `handles zero width characters inside the tags`() {
        val parser = TextToolCallParser(listOf(echo))
        // These models sometimes wedge U+200B into the tag itself; matching must survive it.
        val zwsp = "\u200b"
        val out = parts(
            parser,
            "<${zwsp}tool_call>{\"name\":\"agent-core\",\"input\":{\"task\":\"y\"}}</${zwsp}tool_call>"
        )
        assertEquals("agent-core", out.filterIsInstance<UIMessagePart.Tool>().single().toolName)
    }

    @Test
    fun `streams plain prose untouched when there is no tool call`() {
        val parser = TextToolCallParser(listOf(echo))
        val out = parts(parser, "Hello there, general Kenobi")
        val text = out.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
        assertEquals("Hello there, general Kenobi", text)
        assertTrue(out.none { it is UIMessagePart.Tool })
        assertNull(parser.consumePendingFinishReason())
    }

    @Test
    fun `refuses a tool that was never offered`() {
        val parser = TextToolCallParser(listOf(echo))
        val out = parts(
            parser,
            "<tool_call>{\"name\":\"rm-rf-slash\",\"input\":{}}</tool_call>"
        )
        // Not dispatched — surfaced as visible text instead.
        assertTrue(out.none { it is UIMessagePart.Tool })
        assertTrue(out.filterIsInstance<UIMessagePart.Text>().any { it.text.contains("rm-rf-slash") })
    }

    @Test
    fun `parses the arguments key as well as input`() {
        val parser = TextToolCallParser(listOf(echo))
        val out = parts(
            parser,
            "<tool_call>{\"name\":\"agent-core\",\"arguments\":{\"task\":\"z\"}}</tool_call>"
        )
        assertEquals("agent-core", out.filterIsInstance<UIMessagePart.Tool>().single().toolName)
    }

    @Test
    fun `nests a primitive input under the first required parameter`() {
        val parser = TextToolCallParser(listOf(echo))
        val out = parts(
            parser,
            "<tool_call>{\"name\":\"agent-core\",\"input\":\"battery level\"}</tool_call>"
        )
        val tool = out.filterIsInstance<UIMessagePart.Tool>().single()
        assertTrue(tool.input.contains("\"task\""))
        assertTrue(tool.input.contains("battery level"))
    }

    @Test
    fun `parses two calls in one block`() {
        val parser = TextToolCallParser(listOf(echo, agent))
        val out = parts(
            parser,
            "<tool_call>{\"name\":\"agent-core\",\"input\":{\"task\":\"1\"}}</tool_call>" +
                "<tool_call>{\"name\":\"autonomous-agent\",\"input\":{\"goal\":\"2\"}}</tool_call>"
        )
        assertEquals(
            listOf("agent-core", "autonomous-agent"),
            out.filterIsInstance<UIMessagePart.Tool>().map { it.toolName }
        )
    }

    @Test
    fun `shows malformed markup as text rather than swallowing it`() {
        val parser = TextToolCallParser(listOf(echo))
        val out = parts(parser, "<tool_call>not json at all</tool_call>")
        assertTrue(out.none { it is UIMessagePart.Tool })
        assertTrue(out.filterIsInstance<UIMessagePart.Text>().any { it.text.contains("not json") })
    }

    /**
     * Scope guard: with the feature disabled (the default) a tag sitting in ordinary prose
     * must stay inert text — this is what stops a model *explaining* the format, or a tag
     * arriving inside fetched web content, from executing a real tool.
     */
    @Test
    fun `decoder without the flag never recovers a text tool call`() {
        val decoder = ChatCompletionsStreamDecoder(
            tools = listOf(echo),
            parseTextToolCalls = false,
        )
        val payload = """
            {"id":"1","choices":[{"delta":{"content":"Here is the format: <tool_call>{\"name\":\"agent-core\",\"input\":{\"task\":\"x\"}}</tool_call>"},"index":0}]}
        """.trimIndent()
        val chunks = decoder.accept(SseEvent(null, null, payload)).chunks
        val recovered = chunks.filterIsInstance<StreamChunk.ToolCallStart>()
        assertTrue(recovered.isEmpty())
    }

    @Test
    fun `decoder with the flag recovers a text tool call`() {
        val decoder = ChatCompletionsStreamDecoder(
            tools = listOf(echo),
            parseTextToolCalls = true,
        )
        val payload = """
            {"id":"1","choices":[{"delta":{"content":"<tool_call>{\"name\":\"agent-core\",\"input\":{\"task\":\"x\"}}</tool_call>"},"index":0}]}
        """.trimIndent()
        val chunks = decoder.accept(SseEvent(null, null, payload)).chunks
        assertTrue(chunks.filterIsInstance<StreamChunk.ToolCallStart>().isNotEmpty())
    }
}
