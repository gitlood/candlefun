package com.example.survivor

import com.example.platform.report.HealthSummary
import com.example.platform.report.Telemetry
import java.util.Locale

object SurvivorReport {
    fun print(summary: SurvivorKpiSummary, label: String = "SURVIVOR KPI") {
        Telemetry.emit(
            type = "kpi_snapshot",
            tsMs = System.currentTimeMillis(),
            data = mapOf(
                "strategy_id" to "survivor",
                "mode" to inferMode(label),
                "realized_funding" to summary.realizedFunding,
                "realized_fees" to summary.realizedFees,
                "borrow_costs" to summary.borrowCosts,
                "net_carry" to summary.netCarry,
                "expected_carry" to summary.expectedCarry,
                "worst_basis_abs_pct" to summary.worstBasisAbsPct,
                "cancel_rate" to summary.cancelRate,
                "stale_cancel_rate" to summary.staleCancelRate
            )
        )
        println(render(summary, label))
    }

    fun render(summary: SurvivorKpiSummary, label: String = "SURVIVOR KPI"): String {
        val line = "-".repeat(78)
        val header = String.format(Locale.US, "%-78s", label)
        val cancelRate = summary.cancelRate?.let { "%.2f%%".format(Locale.US, it * 100.0) } ?: "NA"
        val staleRate = summary.staleCancelRate?.let { "%.2f%%".format(Locale.US, it * 100.0) } ?: "NA"
        val mode = inferMode(label)

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
            appendLine(
                HealthSummary.render(
                    strategy = "survivor",
                    mode = mode,
                    net = summary.netCarry,
                    fees = summary.realizedFees + summary.borrowCosts,
                    adverseBps = null,
                    fills = null,
                    exposure = null,
                    extra = mapOf(
                        "worst_basis_pct" to "%.4f".format(Locale.US, summary.worstBasisAbsPct),
                        "expected_carry" to "%.4f".format(Locale.US, summary.expectedCarry)
                    )
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
