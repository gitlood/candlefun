package com.example.platform.report

import java.util.Locale

object HealthSummary {
    fun render(
        strategy: String,
        mode: String,
        net: Double? = null,
        fees: Double? = null,
        adverseBps: Double? = null,
        fills: Int? = null,
        exposure: Double? = null,
        extra: Map<String, String> = emptyMap()
    ): String {
        val parts = mutableListOf(
            "strategy=$strategy",
            "mode=$mode"
        )
        if (net != null) parts.add("net=${fmt(net, 4)}")
        if (fees != null) parts.add("fees=${fmt(fees, 4)}")
        if (adverseBps != null) parts.add("adv_bps=${fmt(adverseBps, 2)}")
        if (fills != null) parts.add("fills=$fills")
        if (exposure != null) parts.add("exposure=${fmt(exposure, 4)}")
        extra.forEach { (k, v) -> parts.add("$k=$v") }
        return "health_summary: " + parts.joinToString(" ")
    }

    private fun fmt(value: Double, decimals: Int): String {
        return String.format(Locale.US, "%.${decimals}f", value)
    }
}
