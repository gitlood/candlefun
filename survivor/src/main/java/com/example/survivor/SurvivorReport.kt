package com.example.survivor

import java.util.Locale

object SurvivorReport {
    fun print(summary: SurvivorKpiSummary, label: String = "SURVIVOR KPI") {
        println(render(summary, label))
    }

    fun render(summary: SurvivorKpiSummary, label: String = "SURVIVOR KPI"): String {
        val line = "-".repeat(78)
        val header = String.format(Locale.US, "%-78s", label)
        val cancelRate = summary.cancelRate?.let { "%.2f%%".format(Locale.US, it * 100.0) } ?: "NA"
        val staleRate = summary.staleCancelRate?.let { "%.2f%%".format(Locale.US, it * 100.0) } ?: "NA"

        return buildString {
            appendLine(line)
            appendLine(header)
            appendLine(line)
            appendLine(formatRow("NET CARRY", summary.netCarry, "FUNDING", summary.realizedFunding))
            appendLine(formatRow("FEES", summary.realizedFees, "BORROW", summary.borrowCosts))
            appendLine(formatRow("EXPECTED", summary.expectedCarry, "WORST BASIS %", summary.worstBasisAbsPct))
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

    private fun formatRow(
        leftLabel: String,
        leftValue: Double,
        rightLabel: String,
        rightValue: Double
    ): String {
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
