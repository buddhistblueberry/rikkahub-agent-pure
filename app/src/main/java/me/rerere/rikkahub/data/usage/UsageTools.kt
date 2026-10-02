package me.rerere.rikkahub.data.usage

import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.local.AgentWorkspace

/** Where an export lands unless the caller says otherwise; ~ is the agent workspace. */
private const val EXPORT_DEFAULT_DIR = "~/exports"

/**
 * P2-11d-2 - hand the accounting ledger to the agent as files.
 *
 * A read of the local ledger plus a write inside the agent workspace, so it is not in the
 * approval set: it cannot reach user data or the network, and the rows it writes are the
 * ones the user is already paying for.
 */
fun usageExportTool(usageRecordDao: UsageRecordDao): Tool = Tool(
    name = "usage_export",
    description = """
        Export the usage ledger to CSV and/or JSON. One row per model round trip: purpose, provider, model, input/output/cached tokens, and the cost frozen when the call was made. Metadata only, never message content. Writes into the agent workspace and replies with the file paths, the row count and the totals.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("format") {
                    put("type", "string")
                    put("description", "csv, json or both. Default both.")
                    put("enum", buildJsonArray {
                        add("csv")
                        add("json")
                        add("both")
                    })
                }
                putJsonObject("since_days") {
                    put("type", "integer")
                    put("description", "Only rows newer than this many days. Default 90, the retention window. 0 means every row still kept.")
                }
                putJsonObject("path") {
                    put("type", "string")
                    put("description", "Destination directory; ~ expands to the agent workspace. Default $EXPORT_DEFAULT_DIR")
                }
            }
        )
    },
    execute = { arguments ->
        val args = arguments as? JsonObject ?: JsonObject(emptyMap())
        val format = UsageExportFormat.fromWire(args["format"]?.jsonPrimitive?.contentOrNull)
            ?: UsageExportFormat.BOTH
        val sinceDays = args["since_days"]?.jsonPrimitive?.intOrNull ?: 90
        val now = System.currentTimeMillis()
        val sinceMs = if (sinceDays <= 0) 0L else now - sinceDays * 86_400_000L

        val rows = usageRecordDao.since(sinceMs, UsageLedgerDefaults.QUERY_LIMIT)
        val dir = File(
            AgentWorkspace.expand(
                args["path"]?.jsonPrimitive?.contentOrNull ?: EXPORT_DEFAULT_DIR
            )
        )
        val written = mutableListOf<File>()
        val failure = runCatching {
            if (!dir.isDirectory && !dir.mkdirs()) {
                error("cannot create directory ${dir.absolutePath}")
            }
            if (format.wantsCsv) {
                val file = File(dir, UsageExport.defaultFileName(UsageExportFormat.CSV, now))
                file.writeText(UsageExport.toCsv(rows))
                written += file
            }
            if (format.wantsJson) {
                val file = File(dir, UsageExport.defaultFileName(UsageExportFormat.JSON, now))
                file.writeText(UsageExport.toJson(rows))
                written += file
            }
        }.exceptionOrNull()

        val payload = if (failure != null) {
            buildJsonObject {
                put("ok", false)
                put("error", "export_failed")
                put("detail", failure.message ?: failure::class.simpleName.orEmpty())
            }
        } else {
            val priced = rows.mapNotNull { it.costMicros }
            buildJsonObject {
                put("ok", true)
                put("rows", rows.size)
                put("truncated_at_limit", rows.size >= UsageLedgerDefaults.QUERY_LIMIT)
                putJsonArray("files") {
                    written.forEach { add(it.absolutePath) }
                }
                putJsonObject("window") {
                    put("since_ms", sinceMs)
                    put("until_ms", now)
                }
                putJsonObject("totals") {
                    put("input_tokens", rows.sumOf { it.inputTokens })
                    put("output_tokens", rows.sumOf { it.outputTokens })
                    put("cached_tokens", rows.sumOf { it.cachedTokens })
                    put("cost_micros", priced.sum())
                    put("costed_rows", priced.size)
                    put("unpriced_rows", rows.size - priced.size)
                }
            }
        }
        listOf(UIMessagePart.Text(payload.toString()))
    },
)
