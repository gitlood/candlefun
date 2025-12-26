package com.example.ofi.kukanov

data class OfiStrategyConfig(
    val symbol: String,
    val orderQty: Double = 0.001,
    val priceTick: Double = 0.01,
    val qtyStep: Double = 0.0001,
    val entryThreshold: Double = 0.002,
    val exitThreshold: Double = 0.0005,
    val takeThresholdMultiplier: Double = 2.5,
    val takeMinEdgeBps: Double = 2.0,
    val maxHoldMs: Long = 1_000L,
    val minSignalIntervalMs: Long = 100L,
    val orderTtlMs: Long = 300L,
    val takeOrderTtlMs: Long = 150L,
    val spreadWindowMs: Long = 1_000L,
    val maxSpreadPct: Double? = 0.002,
    val maxSpreadDeviationPct: Double = 0.2,
    val minSpreadSamples: Int = 5,
    val minDepthNotional: Double? = null,
    val minDepthQty: Double? = null,
    val useTradeConfirm: Boolean = false,
    val minTradeCount1s: Int = 3,
    val minTradeImbalance1s: Double = 0.0,
    val joinOffsetTicks: Int = 0,
    val orderStyle: OfiOrderStyle = OfiOrderStyle.JOIN,
    val logSignals: Boolean = false
) {
    init {
        require(orderQty > 0.0) { "orderQty must be > 0" }
        require(priceTick > 0.0) { "priceTick must be > 0" }
        require(qtyStep > 0.0) { "qtyStep must be > 0" }
        require(entryThreshold > 0.0) { "entryThreshold must be > 0" }
        require(exitThreshold >= 0.0) { "exitThreshold must be >= 0" }
        require(takeThresholdMultiplier >= 1.0) { "takeThresholdMultiplier must be >= 1" }
        require(takeMinEdgeBps >= 0.0) { "takeMinEdgeBps must be >= 0" }
        require(maxHoldMs > 0L) { "maxHoldMs must be > 0" }
        require(minSignalIntervalMs >= 0L) { "minSignalIntervalMs must be >= 0" }
        require(orderTtlMs >= 0L) { "orderTtlMs must be >= 0" }
        require(takeOrderTtlMs >= 0L) { "takeOrderTtlMs must be >= 0" }
        require(spreadWindowMs > 0L) { "spreadWindowMs must be > 0" }
        require(maxSpreadDeviationPct >= 0.0) { "maxSpreadDeviationPct must be >= 0" }
        require(minSpreadSamples >= 1) { "minSpreadSamples must be >= 1" }
        require(minTradeCount1s >= 0) { "minTradeCount1s must be >= 0" }
        require(joinOffsetTicks >= 0) { "joinOffsetTicks must be >= 0" }
    }
}

enum class OfiOrderStyle {
    JOIN,
    TAKE
}
