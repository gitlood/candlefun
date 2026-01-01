package com.example.vacuum

data class VacuumConfig(
    val symbol: String,
    val depthLevels: Int = 5,
    val depthWindowMs: Long = 500L,
    val depthDropPct: Double = 0.5,
    val depthRefillPct: Double = 0.2,
    val spreadWindowMs: Long = 500L,
    val spreadWidenPct: Double = 0.5,
    val maxSpreadPct: Double = 0.005,
    val minTradeCount1s: Int = 8,
    val minTradeImbalance1s: Double = 1.0,
    val orderQty: Double = 0.001,
    val priceTick: Double = 0.01,
    val qtyStep: Double = 0.0001,
    val entryCooldownMs: Long = 300L,
    val orderTtlMs: Long = 150L,
    val maxHoldMs: Long = 1_000L,
    val trailingStopBps: Double = 25.0,
    val slippagePauseBps: Double = 15.0,
    val tailLossBps: Double = 40.0,
    val maxTailLosses: Int = 2,
    val pauseMs: Long = 5 * 60 * 1000L,
    val logSignals: Boolean = false
) {
    init {
        require(depthLevels > 0) { "depthLevels must be > 0" }
        require(depthWindowMs > 0L) { "depthWindowMs must be > 0" }
        require(depthDropPct > 0.0) { "depthDropPct must be > 0" }
        require(depthRefillPct >= 0.0) { "depthRefillPct must be >= 0" }
        require(spreadWindowMs > 0L) { "spreadWindowMs must be > 0" }
        require(spreadWidenPct >= 0.0) { "spreadWidenPct must be >= 0" }
        require(maxSpreadPct > 0.0) { "maxSpreadPct must be > 0" }
        require(minTradeCount1s >= 0) { "minTradeCount1s must be >= 0" }
        require(minTradeImbalance1s >= 0.0) { "minTradeImbalance1s must be >= 0" }
        require(orderQty > 0.0) { "orderQty must be > 0" }
        require(priceTick > 0.0) { "priceTick must be > 0" }
        require(qtyStep > 0.0) { "qtyStep must be > 0" }
        require(entryCooldownMs >= 0L) { "entryCooldownMs must be >= 0" }
        require(orderTtlMs >= 0L) { "orderTtlMs must be >= 0" }
        require(maxHoldMs > 0L) { "maxHoldMs must be > 0" }
        require(trailingStopBps >= 0.0) { "trailingStopBps must be >= 0" }
        require(slippagePauseBps >= 0.0) { "slippagePauseBps must be >= 0" }
        require(tailLossBps >= 0.0) { "tailLossBps must be >= 0" }
        require(maxTailLosses >= 0) { "maxTailLosses must be >= 0" }
        require(pauseMs >= 0L) { "pauseMs must be >= 0" }
    }
}
