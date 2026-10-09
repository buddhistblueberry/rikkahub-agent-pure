package me.rerere.ai.provider.providers.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

/**
 * Streaming fallback parser for models that emit tool calls as literal text in the message
 * content instead of as native OpenAI `tool_calls` deltas.
 *
 * Some OpenAI-compatible gateways (notably the free tier behind Opencode Zen) serve a
 * MiniMax-based model whose chat template asks for tool calls in a `<tool_call>{...}</tool_call>`
 * block. The gateway streams those blocks as ordinary content text, so [ChatCompletionsStreamDecoder]
 * sees no structured `tool_calls` and the markup would otherwise be shown to the user verbatim.
 *
 * This parser splits the content stream into plain text and complete tool-call blocks. Because
 * tags can straddle chunk boundaries it keeps an internal buffer, holding back only the few
 * characters that could still be the start of an opening tag. Text outside a tag is flushed as it
 * arrives so ordinary answers still stream normally.
 *
 * Behaviour is deliberately conservative: it only activates for well-formed blocks whose payload
 * names a tool we were actually offered, and anything it cannot parse is passed through unchanged
 * as text so nothing is silently swallowed.
 *
 * This mirrors the on-device [me.rerere.ai.provider.providers.AICoreProvider] parser, which solves
 * the same problem for Gemini Nano.
 */
internal class TextToolCallParser(private val tools: List<Tool> = emptyList()) {
    private val buffer = StringBuilder()
    private var inToolCall = false
    private var pendingFinishReason: String? = null
    private var sawToolCall = false

    /**
     * Canonical spellings, used when we have to echo a block back to the user.
     * Some of these models emit the tag with zero-width characters wedged inside it
     * (e.g. `<\u200btool_call>`), so matching is done against a normalised copy of the
     * stream rather than the raw one. See [normalize].
     */
    private val openTag = "<tool_call>"
    private val closeTag = "</tool_call>"

    /**
     * Strip the invisible characters these models sprinkle into markup. Zero-width space /
     * non-joiner / joiner and the BOM are the ones seen in the wild; stripping them here means
     * a block is recognised whether or not the model injected them.
     */
    private fun normalize(input: String): String {
        if (input.none { it in ZERO_WIDTH }) return input
        return buildString(input.length) {
            for (c in input) if (c !in ZERO_WIDTH) append(c)
        }
    }

    /** True once at least one tool call was recognised — lets the caller skip text fallbacks. */
    val detected: Boolean get() = sawToolCall

    fun feed(delta: String): List<UIMessagePart> {
        if (delta.isEmpty()) return emptyList()
        buffer.append(normalize(delta))
        val out = mutableListOf<UIMessagePart>()
        while (true) {
            if (!inToolCall) {
                val openIdx = buffer.indexOf(openTag)
                if (openIdx < 0) {
                    // No opening tag in sight. Flush everything except a possible partial tag
                    // at the tail, which we keep buffered until the next chunk arrives.
                    val safe = buffer.length - (openTag.length - 1).coerceAtLeast(0)
                    if (safe > 0) {
                        val text = buffer.substring(0, safe)
                        buffer.delete(0, safe)
                        if (text.isNotEmpty()) out += UIMessagePart.Text(text)
                    }
                    break
                }
                if (openIdx > 0) {
                    val pre = buffer.substring(0, openIdx)
                    if (pre.isNotEmpty()) out += UIMessagePart.Text(pre)
                }
                buffer.delete(0, openIdx + openTag.length)
                inToolCall = true
            }
            // Inside a tool call — hold characters until the closing tag shows up.
            val closeIdx = buffer.indexOf(closeTag)
            if (closeIdx < 0) break
            val body = buffer.substring(0, closeIdx).trim()
            buffer.delete(0, closeIdx + closeTag.length)
            inToolCall = false
            val parsed = parseToolCallBody(body)
            if (parsed != null) {
                out += parsed
                pendingFinishReason = "tool_calls"
                sawToolCall = true
            } else {
                // Unparseable — show it as text so the model's intent is still visible.
                out += UIMessagePart.Text("$openTag$body$closeTag")
            }
        }
        return out
    }

    /** Flush whatever is left when the stream ends (e.g. an unterminated tag). */
    fun flushPending(): List<UIMessagePart> {
        if (buffer.isEmpty()) return emptyList()
        val txt = buffer.toString()
        buffer.clear()
        inToolCall = false
        return listOf(UIMessagePart.Text(txt))
    }

    fun consumePendingFinishReason(): String? {
        val r = pendingFinishReason
        pendingFinishReason = null
        return r
    }

    private fun parseToolCallBody(body: String): UIMessagePart.Tool? = try {
        val obj: JsonObject = parseLenient(body) ?: return null
        val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return null
        // Only accept tools that were actually offered. Without this check a model could
        // invoke anything by name and we would happily dispatch it.
        if (tools.none { it.name == name }) return null
        val inputJson = when (val raw = obj["input"] ?: obj["arguments"]) {
            null -> "{}"
            is JsonObject -> raw.toString()
            is JsonPrimitive -> if (raw.isString) {
                wrapPrimitiveInput(name, raw.content)
            } else {
                raw.toString()
            }
            else -> raw.toString()
        }
        UIMessagePart.Tool(
            toolCallId = "text-tool-${System.nanoTime()}",
            toolName = name,
            input = inputJson,
            output = emptyList(),
        )
    } catch (_: Throwable) {
        null
    }

    /**
     * The model emitted `"input": "<string>"` rather than an object. Look up the named tool's
     * schema and nest the string under its first required property, falling back to "command"
     * (the parameter most single-argument tools take).
     */
    private fun wrapPrimitiveInput(toolName: String, value: String): String {
        val key = inferPrimaryParamKey(toolName) ?: "command"
        return buildString {
            append("{")
            append('"').append(key).append("\":")
            append(JsonPrimitive(value))
            append("}")
        }
    }

    private fun inferPrimaryParamKey(toolName: String): String? {
        val tool = tools.firstOrNull { it.name == toolName } ?: return null
        val schema = runCatching { tool.parameters() }.getOrNull() as? InputSchema.Obj
            ?: return null
        schema.required?.firstOrNull()?.let { return it }
        return schema.properties.keys.firstOrNull()
    }

    /**
     * Parse [body] as a JSON object, repairing the malformations these models actually emit:
     * wrong closing punctuation (`}>` instead of `}}`), unbalanced braces, trailing commas.
     * Returns null when no repair succeeds, so the caller can fall back to plain text.
     */
    private fun parseLenient(body: String): JsonObject? {
        val candidates = buildList {
            add(body)
            add(body.replace(Regex("""\}\s*>\s*$"""), "}}"))
            add(body.replace("}>", "}}"))
            add(body.replace(Regex(""",\s*\}"""), "}").replace(Regex(""",\s*\]"""), "]"))
            run {
                val opens = body.count { it == '{' }
                val closes = body.count { it == '}' }
                if (opens > closes) add(body + "}".repeat(opens - closes))
            }
        }
        for (variant in candidates.distinct()) {
            try {
                return kotlinx.serialization.json.Json.parseToJsonElement(variant) as? JsonObject
            } catch (_: Throwable) {
                // try the next repair
            }
        }
        return null
    }

    private companion object {
        val ZERO_WIDTH = setOf('\u200B', '\u200C', '\u200D', '\u2060', '\uFEFF')
    }
}
