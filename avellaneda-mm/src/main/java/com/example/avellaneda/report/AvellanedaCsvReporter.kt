package com.example.avellaneda.report

import java.io.File
import java.io.FileWriter
import java.util.Locale

data class AvellanedaReportRow(
    val timestampMs: Long,
    val symbol: String,
    val mid: Double?,
    val qty: Double,
    val avg: Double,
    val unrealizedPnl: Double,
    val realizedPnl: Double,
    val netPnl: Double,
    val pnlPct: Double,
    val exposure: Double,
    val fills: Int?,
    val makerFills: Int?,
    val takerFills: Int?,
    val totalFees: Double?,
    val totalNotional: Double?,
    val advBps: List<Double?>
)

class AvellanedaCsvReporter private constructor(
    private val file: File,
    private val mode: String,
    private val advLabels: List<String>
) {
    fun reportPath(): String = file.absolutePath

    fun write(rows: List<AvellanedaReportRow>) {
        if (rows.isEmpty()) return
        ensureHeader()
        FileWriter(file, true).use { writer ->
            rows.forEach { row ->
                writer.appendLine(renderRow(row))
            }
        }
    }

    private fun ensureHeader() {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        if (file.exists() && file.length() > 0L) return
        FileWriter(file, true).use { writer ->
            writer.appendLine(headerLine())
        }
    }

    private fun headerLine(): String {
        val cols = mutableListOf(
            "ts_ms",
            "mode",
            "symbol",
            "mid",
            "qty",
            "avg",
            "unrealized_pnl",
            "realized_pnl",
            "net_pnl",
            "pnl_pct",
            "exposure",
            "fills",
            "maker_fills",
            "taker_fills",
            "total_fees",
            "total_notional"
        )
        advLabels.forEach { label ->
            cols.add("adv_${label}_bps")
        }
        return cols.joinToString(",")
    }

    private fun renderRow(row: AvellanedaReportRow): String {
        val adv = padAdv(row.advBps)
        val cols = listOf(
            row.timestampMs.toString(),
            mode,
            row.symbol,
            fmt(row.mid, 6),
            fmt(row.qty, 6),
            fmt(row.avg, 6),
            fmt(row.unrealizedPnl, 4),
            fmt(row.realizedPnl, 4),
            fmt(row.netPnl, 4),
            fmt(row.pnlPct, 4),
            fmt(row.exposure, 4),
            row.fills?.toString() ?: "",
            row.makerFills?.toString() ?: "",
            row.takerFills?.toString() ?: "",
            fmt(row.totalFees, 6),
            fmt(row.totalNotional, 4)
        ).toMutableList()
        adv.forEach { value ->
            cols.add(fmt(value, 3))
        }
        return cols.joinToString(",")
    }

    private fun padAdv(values: List<Double?>): List<Double?> {
        if (values.size >= advLabels.size) return values.take(advLabels.size)
        return values + List(advLabels.size - values.size) { null }
    }

    private fun fmt(value: Double?, decimals: Int): String {
        if (value == null) return ""
        return String.format(Locale.US, "%.${decimals}f", value)
    }

    companion object {
        fun fromEnv(mode: String, advLabels: List<String>): AvellanedaCsvReporter? {
            val enabled = System.getenv("REPORT_ENABLED")?.toBooleanStrictOrNull() ?: true
            if (!enabled) return null
            val path = System.getenv("AVELLANEDA_REPORT_PATH")
                ?: System.getenv("REPORT_PATH")
                ?: run {
                    val dir = System.getenv("REPORT_DIR")
                    if (!dir.isNullOrBlank()) {
                        File(dir, "avellaneda_${mode}.csv").absolutePath
                    } else {
                        defaultReportPath(mode)
                    }
                }
            return AvellanedaCsvReporter(File(path), mode, advLabels)
        }

        private fun defaultReportPath(mode: String): String {
            val root = findProjectRoot()
            val dir = File(root, "reports/avellaneda")
            return File(dir, "avellaneda_${mode}.csv").absolutePath
        }

        private fun findProjectRoot(): File {
            var dir = File(System.getProperty("user.dir"))
            while (true) {
                if (File(dir, "settings.gradle.kts").exists()) return dir
                val parent = dir.parentFile ?: return dir
                dir = parent
            }
        }
    }
}
