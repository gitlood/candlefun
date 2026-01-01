package com.example.platform.report

import java.io.File
import java.util.Locale

data class RunSummary(
    val strategy: String,
    val mode: String,
    val timestampMs: Long,
    val configs: Map<String, String>,
    val metrics: Map<String, Any?>,
    val health: Map<String, Any?>,
    val notes: Map<String, String> = emptyMap()
)

object RunSummaryWriter {
    private val jsonEscapes = mapOf(
        '\\' to "\\\\",
        '"' to "\\\"",
        '\n' to "\\n",
        '\r' to "\\r",
        '\t' to "\\t"
    )

    fun writeSummary(root: File, summary: RunSummary) {
        val aiReport = AiRunReportWriter.writeReport(root, summary)
        println("ReportPath   : ${aiReport.absolutePath}")
    }

    private fun encodeSummary(summary: RunSummary): String {
        val builder = StringBuilder()
        builder.append("{")
        builder.append("\"strategy\":").append(jsonString(summary.strategy)).append(",")
        builder.append("\"mode\":").append(jsonString(summary.mode)).append(",")
        builder.append("\"timestampMs\":").append(summary.timestampMs).append(",")
        builder.append("\"configs\":").append(mapToJson(summary.configs)).append(",")
        builder.append("\"metrics\":").append(mapToJson(summary.metrics)).append(",")
        builder.append("\"health\":").append(mapToJson(summary.health))
        if (summary.notes.isNotEmpty()) {
            builder.append(",\"notes\":").append(mapToJson(summary.notes))
        }
        builder.append("}")
        return builder.toString()
    }

    private fun mapToJson(map: Map<String, *>): String {
        val entries = map.map { (key, value) ->
            "\"${escape(key)}\":${valueToJson(value)}"
        }
        return "{${entries.joinToString(",")}}"
    }

    private fun valueToJson(value: Any?): String {
        return when (value) {
            null -> "null"
            is Number -> formatNumber(value)
            is Boolean -> value.toString()
            is String -> jsonString(value)
            is Map<*, *> -> mapToJson(value.filterKeys { it is String }.mapKeys { it.key as String })
            is Iterable<*> -> iterableToJson(value)
            else -> jsonString(value.toString())
        }
    }

    private fun iterableToJson(iterable: Iterable<*>): String {
        val items = iterable.map { valueToJson(it) }
        return "[${items.joinToString(",")}]"
    }

    private fun formatNumber(value: Number): String {
        return when (value) {
            is Float, is Double -> {
                if (value.toDouble().isFinite()) {
                    String.format(Locale.US, "%.6f", value.toDouble())
                } else {
                    "null"
                }
            }
            else -> value.toString()
        }
    }

    private fun jsonString(raw: String): String {
        val builder = StringBuilder(raw.length + 8)
        builder.append('"')
        raw.forEach { ch ->
            builder.append(jsonEscapes[ch] ?: ch)
        }
        builder.append('"')
        return builder.toString()
    }

    private fun escape(raw: String): String {
        val builder = StringBuilder(raw.length + 4)
        raw.forEach { ch ->
            builder.append(jsonEscapes[ch] ?: ch)
        }
        return builder.toString()
    }
}
