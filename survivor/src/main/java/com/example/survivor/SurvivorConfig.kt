package com.example.survivor

data class SurvivorConfig(
    val symbol: String,
    val orderQty: Double = 0.001,
    val entryFundingThreshold: Double = 0.0005,
    val exitFundingThreshold: Double = 0.0002,
    val basisStopAbsPct: Double = 0.01,
    val maxVolatility: Double = 0.02,
    val maxSpreadPct: Double = 0.001,
    val maxOiJumpPct: Double = 0.2,
    val oiWindowMs: Long = 60_000L,
    val maxHoldMs: Long = 6 * 60 * 60 * 1000L,
    val orderTtlMs: Long = 5_000L,
    val makerFeePct: Double = 0.0002,
    val takerFeePct: Double = 0.0004,
    val borrowFeePctPerDay: Double = 0.0005,
    val allowHedge: Boolean = false,
    val logSignals: Boolean = false
) {
    init {
        require(orderQty > 0.0) { "orderQty must be > 0" }
        require(entryFundingThreshold > 0.0) { "entryFundingThreshold must be > 0" }
        require(exitFundingThreshold >= 0.0) { "exitFundingThreshold must be >= 0" }
        require(basisStopAbsPct > 0.0) { "basisStopAbsPct must be > 0" }
        require(maxVolatility > 0.0) { "maxVolatility must be > 0" }
        require(maxSpreadPct > 0.0) { "maxSpreadPct must be > 0" }
        require(maxOiJumpPct >= 0.0) { "maxOiJumpPct must be >= 0" }
        require(oiWindowMs > 0L) { "oiWindowMs must be > 0" }
        require(maxHoldMs > 0L) { "maxHoldMs must be > 0" }
        require(orderTtlMs >= 0L) { "orderTtlMs must be >= 0" }
        require(makerFeePct >= 0.0) { "makerFeePct must be >= 0" }
        require(takerFeePct >= 0.0) { "takerFeePct must be >= 0" }
        require(borrowFeePctPerDay >= 0.0) { "borrowFeePctPerDay must be >= 0" }
    }
}
