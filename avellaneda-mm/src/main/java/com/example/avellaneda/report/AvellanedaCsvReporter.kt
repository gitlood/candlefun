package com.example.avellaneda.report

import com.example.platform.report.Telemetry
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
                Telemetry.emit(
                    type = "kpi_snapshot",
                    tsMs = row.timestampMs,
                    data = mapOf(
                        "strategy_id" to "avellaneda",
                        "symbol" to row.symbol,
                        "mid" to row.mid,
                        "qty" to row.qty,
                        "avg_price" to row.avg,
                        "unrealized_pnl" to row.unrealizedPnl,
                        "realized_pnl" to row.realizedPnl,
                        "net_pnl" to row.netPnl,
                        "pnl_pct" to row.pnlPct,
                        "exposure" to row.exposure,
                        "fills" to row.fills,
                        "maker_fills" to row.makerFills,
                        "taker_fills" to row.takerFills,
                        "total_fees" to row.totalFees,
                        "total_notional" to row.totalNotional,
                        "adv_bps" to row.advBps
                    )
                )
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
            val truncate = System.getenv("REPORT_TRUNCATE")?.toBooleanStrictOrNull() ?: true
            val timestamped = System.getenv("REPORT_TIMESTAMPED")?.toBooleanStrictOrNull() ?: false
            val path = System.getenv("AVELLANEDA_REPORT_PATH")
                ?: System.getenv("REPORT_PATH")
                ?: run {
                    val dir = System.getenv("REPORT_DIR")
                    if (!dir.isNullOrBlank()) {
                        File(dir, reportFileName(mode, timestamped)).absolutePath
                    } else {
                        defaultReportPath(mode, timestamped)
                    }
                }
            val file = File(path)
            if (truncate && file.exists()) {
                file.delete()
            }
            return AvellanedaCsvReporter(file, mode, advLabels)
        }

        private fun defaultReportPath(mode: String, timestamped: Boolean): String {
            val root = findProjectRoot()
            val dir = File(root, "reports/avellaneda")
            return File(dir, reportFileName(mode, timestamped)).absolutePath
        }

        private fun reportFileName(mode: String, timestamped: Boolean): String {
            if (!timestamped) return "avellaneda_${mode}.csv"
            val ts = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
                .withZone(java.time.ZoneOffset.UTC)
                .format(java.time.Instant.now())
            return "avellaneda_${mode}_$ts.csv"
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
