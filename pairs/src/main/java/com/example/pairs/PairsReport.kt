package com.example.pairs

import com.example.platform.report.HealthSummary
import com.example.platform.report.Telemetry
import java.util.Locale

object PairsReport {
    fun print(summary: PairsKpiSummary, label: String = "PAIRS KPI") {
        Telemetry.emit(
            type = "kpi_snapshot",
            tsMs = System.currentTimeMillis(),
            data = mapOf(
                "strategy_id" to "pairs",
                "mode" to inferMode(label),
                "avg_half_life_ms" to summary.avgHalfLifeMs,
                "tail_events" to summary.tailEvents,
                "realized_pnl" to summary.realizedPnL,
                "total_fees" to summary.totalFees,
                "net_pnl" to summary.netPnL
            )
        )
        println(render(summary, label))
    }

    fun render(summary: PairsKpiSummary, label: String = "PAIRS KPI"): String {
        val line = "-".repeat(78)
        val header = String.format(Locale.US, "%-78s", label)
        val halfLife = summary.avgHalfLifeMs?.let { "%.1fs".format(Locale.US, it / 1000.0) } ?: "NA"
        val mode = inferMode(label)

        return buildString {
            appendLine(line)
            appendLine(header)
            appendLine(line)
            appendLine(formatRow("NET PNL", summary.netPnL, "FEES", summary.totalFees))
            appendLine(formatRow("REALIZED", summary.realizedPnL, "TAIL EVENTS", summary.tailEvents.toDouble()))
            appendLine(String.format(Locale.US, "HALF-LIFE: %-10s", halfLife))
            appendLine(
                HealthSummary.render(
                    strategy = "pairs",
                    mode = mode,
                    net = summary.netPnL,
                    fees = summary.totalFees,
                    adverseBps = null,
                    fills = null,
                    exposure = null,
                    extra = mapOf(
                        "half_life" to halfLife,
                        "tail_events" to summary.tailEvents.toString()
                    )
                )
            )
            appendLine(line)
        }
    }

    private fun formatRow(leftLabel: String, leftValue: Double, rightLabel: String, rightValue: Double): String {
        return String.format(
            Locale.US,
            "%-14s %12.4f   %-14s %12.4f",
            leftLabel,
            leftValue,
            rightLabel,
            rightValue
        )
    }

    private fun inferMode(label: String): String {
        val upper = label.uppercase(Locale.US)
        return when {
            upper.contains("TESTNET") -> "testnet"
            upper.contains("LIVE") -> "live"
            upper.contains("BACKTEST") -> "backtest"
            else -> "unknown"
        }
    }
}
