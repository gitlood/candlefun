package com.example.survivor

data class SurvivorConfig(
    val symbol: String,
    val orderQty: Double = 0.001,
    val entryFundingThreshold: Double = 0.0005,
    val exitFundingThreshold: Double = 0.0002,
    val entryBasisAbsPctMax: Double = 0.0025,
    val maxTimeToFundingForTakerMs: Long = 15 * 60 * 1000L,
    val basisStopAbsPct: Double = 0.01,
    val maxVolatility: Double = 0.02,
    val maxSpreadPct: Double = 0.001,
    val maxOiJumpPct: Double = 0.2,
    val oiWindowMs: Long = 60_000L,
    val maxHoldMs: Long = 6 * 60 * 60 * 1000L,
    val orderTtlMs: Long = 5_000L,
    val rebalanceIntervalMs: Long = 2_000L,
    val staleFeedMs: Long = 10_000L,
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
        require(entryBasisAbsPctMax >= 0.0) { "entryBasisAbsPctMax must be >= 0" }
        require(maxTimeToFundingForTakerMs >= 0L) { "maxTimeToFundingForTakerMs must be >= 0" }
        require(basisStopAbsPct > 0.0) { "basisStopAbsPct must be > 0" }
        require(maxVolatility > 0.0) { "maxVolatility must be > 0" }
        require(maxSpreadPct > 0.0) { "maxSpreadPct must be > 0" }
        require(maxOiJumpPct >= 0.0) { "maxOiJumpPct must be >= 0" }
        require(oiWindowMs > 0L) { "oiWindowMs must be > 0" }
        require(maxHoldMs > 0L) { "maxHoldMs must be > 0" }
        require(orderTtlMs >= 0L) { "orderTtlMs must be >= 0" }
        require(rebalanceIntervalMs > 0L) { "rebalanceIntervalMs must be > 0" }
        require(staleFeedMs > 0L) { "staleFeedMs must be > 0" }
        require(makerFeePct >= 0.0) { "makerFeePct must be >= 0" }
        require(takerFeePct >= 0.0) { "takerFeePct must be >= 0" }
        require(borrowFeePctPerDay >= 0.0) { "borrowFeePctPerDay must be >= 0" }
    }
}
