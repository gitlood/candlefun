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
        Telemetry.emit(
            type = "health_summary",
            tsMs = System.currentTimeMillis(),
            data = buildTelemetryData(
                strategy = strategy,
                mode = mode,
                net = net,
                fees = fees,
                adverseBps = adverseBps,
                fills = fills,
                exposure = exposure,
                extra = extra
            )
        )
        return "health_summary: " + parts.joinToString(" ")
    }

    private fun fmt(value: Double, decimals: Int): String {
        return String.format(Locale.US, "%.${decimals}f", value)
    }

    private fun buildTelemetryData(
        strategy: String,
        mode: String,
        net: Double?,
        fees: Double?,
        adverseBps: Double?,
        fills: Int?,
        exposure: Double?,
        extra: Map<String, String>
    ): Map<String, Any?> {
        val data = LinkedHashMap<String, Any?>()
        data["strategy_id"] = strategy
        data["mode"] = mode
        if (net != null) data["net"] = net
        if (fees != null) data["fees"] = fees
        if (adverseBps != null) data["adv_bps"] = adverseBps
        if (fills != null) data["fills"] = fills
        if (exposure != null) data["exposure"] = exposure
        if (extra.isNotEmpty()) data["extra"] = extra
        return data
    }
}
