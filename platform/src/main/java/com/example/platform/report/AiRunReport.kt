package com.example.platform.report

import java.io.File
import java.util.Locale

object AiRunReportWriter {
    fun writeReport(root: File, summary: RunSummary): File {
        val dir = resolveReportDir(root)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val filename = "${summary.strategy}_${summary.mode}_${summary.timestampMs}_report.json"
        val file = File(dir, filename)
        file.writeText(buildReport(summary))
        return file
    }

    private fun resolveReportDir(root: File): File {
        val reportDir = System.getenv("REPORT_DIR")?.trim()
        return if (!reportDir.isNullOrBlank()) {
            File(reportDir, "ai_reports")
        } else {
            File(root, "reports/ai_reports")
        }
    }

    private fun buildReport(summary: RunSummary): String {
        val combined = LinkedHashMap<String, Any?>().apply {
            putAll(summary.metrics)
            putAll(summary.health)
        }
        val keyStats = buildKeyStats(combined)
        val telemetryPath = summary.notes["telemetry_path"]
        val telemetryLatest = telemetryPath?.let { readTelemetry(it) }
        val payload = mapOf(
            "type" to "ai_report",
            "timestamp_ms" to summary.timestampMs,
            "strategy" to summary.strategy,
            "mode" to summary.mode,
            "pipeline" to listOf(
                "UniverseSelector: liquidity, tick/step sanity, stable spreads",
                "MarketData: WS feed handlers + local order book + trade tape + user fills stream",
                "FeatureComputer: spread, depth, imbalance, microprice, OFI, vol windows, toxicity/adverse bps",
                "RegimeGates: global + per-strategy (pause/scale/widen)",
                "Strategies: each produces Intents only",
                "IntentNetter / InternalCrosser: net conflicts, attribute fairly, track savings",
                "ExecutionPolicy: maker/taker rules, TTL, cancel logic, cooldowns, precision/filters",
                "RiskManager: position caps, leverage caps, margin buffer, drawdown kill-switch",
                "Metrics: PnL, realized spread, adverse bps, slippage, fill quality, exposure, churn"
            ),
            "advantages" to listOf(
                "gating",
                "inventory-aware skew",
                "adverse selection tracker (MM truth meter)"
            ),
            "overnight_decider" to listOf(
                "If adv1s/adv5s bps is consistently positive: donating liquidity (picked off).",
                "If adverse bps is bad: widen quotes, trade less, strengthen gates, even if fills drop.",
                "More fills is often worse if they are toxic."
            ),
            "key_stats" to keyStats,
            "configs" to summary.configs,
            "metrics" to summary.metrics,
            "health" to summary.health,
            "notes" to summary.notes,
            "telemetry_latest" to telemetryLatest,
            "prompt" to buildPrompt(summary, keyStats)
        )
        return encodeJson(payload)
    }

    private fun buildKeyStats(values: Map<String, Any?>): Map<String, String> {
        val stats = LinkedHashMap<String, String>()
        findFirst(values, listOf("net", "net_pnl", "netPnl"))?.let { stats["pnl"] = it }
        stats["fills"] = resolveFills(values)
        findFirst(values, listOf("avg_adverse_bps", "avg_adverse_move_bps", "adv_bps"))
            ?.let { stats["adverse_bps"] = it }
        findFirst(values, listOf("exposure", "sum_abs_qty", "max_abs_qty", "qty"))
            ?.let { stats["inventory_drift"] = it }
        return stats
    }

    private fun resolveFills(values: Map<String, Any?>): String {
        findFirst(values, listOf("fills", "trade_count", "fills_total"))?.let { return it }
        val maker = findFirst(values, listOf("maker_fills"))?.toLongOrNull()
        val taker = findFirst(values, listOf("taker_fills"))?.toLongOrNull()
        return if (maker != null || taker != null) {
            val total = (maker ?: 0L) + (taker ?: 0L)
            total.toString()
        } else {
            "n/a"
        }
    }

    private fun findFirst(values: Map<String, Any?>, keys: List<String>): String? {
        for (key in keys) {
            if (!values.containsKey(key)) continue
            val value = values[key] ?: continue
            return formatValue(value)
        }
        return null
    }

    private fun formatValue(value: Any?): String {
        return when (value) {
            null -> "n/a"
            is Number -> formatNumber(value)
            else -> value.toString()
        }
    }

    private fun formatNumber(value: Number): String {
        val dbl = value.toDouble()
        if (!dbl.isFinite()) return "n/a"
        return String.format(Locale.US, "%.6f", dbl).trimEnd('0').trimEnd('.')
    }

    private fun renderMap(values: Map<String, *>): String {
        if (values.isEmpty()) return "n/a"
        return values.entries.joinToString("\n") { (key, value) ->
            val rendered = when (value) {
                null -> "n/a"
                is Number -> formatNumber(value)
                else -> value.toString()
            }
            "$key: $rendered"
        }
    }

    private fun buildPrompt(summary: RunSummary, keyStats: Map<String, String>): String {
        return buildString {
            appendLine("CandleFun AI Report")
            appendLine("timestamp_ms: ${summary.timestampMs}")
            appendLine("strategy: ${summary.strategy}")
            appendLine("mode: ${summary.mode}")
            appendLine()
            appendLine("Architecture Pipeline")
            appendLine("- UniverseSelector: liquidity, tick/step sanity, stable spreads")
            appendLine("- MarketData: WS feed handlers + local order book + trade tape + user fills stream")
            appendLine("- FeatureComputer: spread, depth, imbalance, microprice, OFI, vol windows, toxicity/adverse bps")
            appendLine("- RegimeGates: global + per-strategy (pause/scale/widen)")
            appendLine("- Strategies: each produces Intents only")
            appendLine("- IntentNetter / InternalCrosser: net conflicts, attribute fairly, track savings")
            appendLine("- ExecutionPolicy: maker/taker rules, TTL, cancel logic, cooldowns, precision/filters")
            appendLine("- RiskManager: position caps, leverage caps, margin buffer, drawdown kill-switch")
            appendLine("- Metrics: PnL, realized spread, adverse bps, slippage, fill quality, exposure, churn")
            appendLine()
            appendLine("Advantages Already In Place")
            appendLine("- gating")
            appendLine("- inventory-aware skew")
            appendLine("- adverse selection tracker (MM truth meter)")
            appendLine()
            appendLine("Overnight Run Decider")
            appendLine("- If adv1s/adv5s bps is consistently positive: donating liquidity (picked off).")
            appendLine("- If adverse bps is bad: widen quotes, trade less, strengthen gates, even if fills drop.")
            appendLine("- More fills is often worse if they are toxic.")
            appendLine()
            appendLine("Key Stats (latest)")
            appendLine("- pnl: ${keyStats["pnl"] ?: "n/a"}")
            appendLine("- fills: ${keyStats["fills"] ?: "n/a"}")
            appendLine("- adverse_bps: ${keyStats["adverse_bps"] ?: "n/a"}")
            appendLine("- inventory_drift_proxy: ${keyStats["inventory_drift"] ?: "n/a"}")
            appendLine()
            appendLine("Configs")
            appendLine(renderMap(summary.configs))
            appendLine()
            appendLine("Metrics")
            appendLine(renderMap(summary.metrics))
            appendLine()
            appendLine("Health")
            appendLine(renderMap(summary.health))
            if (summary.notes.isNotEmpty()) {
                appendLine()
                appendLine("Notes")
                appendLine(renderMap(summary.notes))
            }
            val telemetryPath = summary.notes["telemetry_path"]
            if (!telemetryPath.isNullOrBlank()) {
                appendLine()
                appendLine("Telemetry Latest")
                appendLine(telemetryPath)
            }
        }
    }

    private fun encodeJson(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> jsonString(value)
            is Boolean -> value.toString()
            is Number -> formatNumber(value)
            is Map<*, *> -> encodeMap(value)
            is Iterable<*> -> encodeList(value)
            is Array<*> -> encodeList(value.asList())
            else -> jsonString(value.toString())
        }
    }

    private fun encodeMap(map: Map<*, *>): String {
        val parts = ArrayList<String>(map.size)
        for ((key, value) in map) {
            val k = key?.toString() ?: continue
            parts.add("${jsonString(k)}:${encodeJson(value)}")
        }
        return "{${parts.joinToString(",")}}"
    }

    private fun encodeList(list: Iterable<*>): String {
        val parts = list.map { encodeJson(it) }
        return "[${parts.joinToString(",")}]"
    }

    private fun jsonString(raw: String): String {
        val sb = StringBuilder(raw.length + 8)
        sb.append('"')
        for (ch in raw) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun readTelemetry(path: String): String? {
        return runCatching {
            val file = File(path)
            if (!file.exists()) return null
            file.readText()
        }.getOrNull()
    }
}
