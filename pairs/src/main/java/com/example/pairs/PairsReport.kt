package com.example.pairs

import java.util.Locale

object PairsReport {
    fun print(summary: PairsKpiSummary, label: String = "PAIRS KPI") {
        println(render(summary, label))
    }

    fun render(summary: PairsKpiSummary, label: String = "PAIRS KPI"): String {
        val line = "-".repeat(78)
        val header = String.format(Locale.US, "%-78s", label)
        val halfLife = summary.avgHalfLifeMs?.let { "%.1fs".format(Locale.US, it / 1000.0) } ?: "NA"

        return buildString {
            appendLine(line)
            appendLine(header)
            appendLine(line)
            appendLine(formatRow("NET PNL", summary.netPnL, "FEES", summary.totalFees))
            appendLine(formatRow("REALIZED", summary.realizedPnL, "TAIL EVENTS", summary.tailEvents.toDouble()))
            appendLine(String.format(Locale.US, "HALF-LIFE: %-10s", halfLife))
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
}
