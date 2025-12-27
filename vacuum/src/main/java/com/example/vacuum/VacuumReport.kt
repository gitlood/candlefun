package com.example.vacuum

import java.util.Locale

object VacuumReport {
    fun print(summary: VacuumKpiSummary, label: String = "VACUUM KPI") {
        println(render(summary, label))
    }

    fun render(summary: VacuumKpiSummary, label: String = "VACUUM KPI"): String {
        val line = "-".repeat(78)
        val header = String.format(Locale.US, "%-78s", label)
        val slip = summary.avgSlippageBps?.let { "%.2f".format(Locale.US, it) } ?: "NA"
        val adverse = summary.avgAdverseMoveBps?.let { "%.2f".format(Locale.US, it) } ?: "NA"
        val cancelRate = summary.cancelRate?.let { "%.2f%%".format(Locale.US, it * 100.0) } ?: "NA"
        val staleRate = summary.staleCancelRate?.let { "%.2f%%".format(Locale.US, it * 100.0) } ?: "NA"
        val lastSlip = summary.lastSlippageBps?.let { "%.2f".format(Locale.US, it) } ?: "NA"

        return buildString {
            appendLine(line)
            appendLine(header)
            appendLine(line)
            appendLine(formatRow("AVG SLIP", slip, "AVG ADVERSE", adverse))
            appendLine(
                String.format(
                    Locale.US,
                    "LAST SLIP: %-10s  TAIL LOSSES: %-5d",
                    lastSlip,
                    summary.tailLossCount
                )
            )
            appendLine(
                String.format(
                    Locale.US,
                    "CANCEL RATE: %-10s  STALE CANCEL: %-10s",
                    cancelRate,
                    staleRate
                )
            )
            appendLine(line)
        }
    }

    private fun formatRow(leftLabel: String, leftValue: String, rightLabel: String, rightValue: String): String {
        return String.format(
            Locale.US,
            "%-14s %12s   %-14s %12s",
            leftLabel,
            leftValue,
            rightLabel,
            rightValue
        )
    }
}
