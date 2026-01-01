package com.example.pairs

data class PairsConfig(
    val symbolA: String,
    val symbolB: String,
    val windowMs: Long = 120_000L,
    val minSamples: Int = 50,
    val entryZ: Double = 2.0,
    val exitZ: Double = 0.3,
    val maxHoldMs: Long = 10 * 60 * 1000L,
    val minCorr: Double = 0.5,
    val maxVol: Double = 0.05,
    val trendCountLimit: Int = 3,
    val notional: Double = 100.0,
    val priceTick: Double = 0.01,
    val qtyStep: Double = 0.0001,
    val priceTickA: Double = priceTick,
    val priceTickB: Double = priceTick,
    val qtyStepA: Double = qtyStep,
    val qtyStepB: Double = qtyStep,
    val minQtyA: Double? = null,
    val minQtyB: Double? = null,
    val minNotionalA: Double? = null,
    val minNotionalB: Double? = null,
    val orderTtlMs: Long = 5_000L,
    val hedgeRepairDelayMs: Long = 1_000L,
    val hedgeRepairCooldownMs: Long = 1_000L,
    val hedgeRepairMinFillPct: Double = 0.5,
    val makerFeePct: Double = 0.0002,
    val takerFeePct: Double = 0.0004,
    val tailZ: Double = 4.0,
    val logSignals: Boolean = false
) {
    init {
        require(windowMs > 0L) { "windowMs must be > 0" }
        require(minSamples >= 5) { "minSamples must be >= 5" }
        require(entryZ > 0.0) { "entryZ must be > 0" }
        require(exitZ >= 0.0) { "exitZ must be >= 0" }
        require(maxHoldMs > 0L) { "maxHoldMs must be > 0" }
        require(minCorr >= 0.0) { "minCorr must be >= 0" }
        require(maxVol > 0.0) { "maxVol must be > 0" }
        require(trendCountLimit >= 0) { "trendCountLimit must be >= 0" }
        require(notional > 0.0) { "notional must be > 0" }
        require(priceTick > 0.0) { "priceTick must be > 0" }
        require(qtyStep > 0.0) { "qtyStep must be > 0" }
        require(priceTickA > 0.0) { "priceTickA must be > 0" }
        require(priceTickB > 0.0) { "priceTickB must be > 0" }
        require(qtyStepA > 0.0) { "qtyStepA must be > 0" }
        require(qtyStepB > 0.0) { "qtyStepB must be > 0" }
        require(orderTtlMs >= 0L) { "orderTtlMs must be >= 0" }
        require(hedgeRepairDelayMs >= 0L) { "hedgeRepairDelayMs must be >= 0" }
        require(hedgeRepairCooldownMs >= 0L) { "hedgeRepairCooldownMs must be >= 0" }
        require(hedgeRepairMinFillPct >= 0.0) { "hedgeRepairMinFillPct must be >= 0" }
        require(makerFeePct >= 0.0) { "makerFeePct must be >= 0" }
        require(takerFeePct >= 0.0) { "takerFeePct must be >= 0" }
        require(tailZ > 0.0) { "tailZ must be > 0" }
    }
}

enum class PairSide {
    LONG_A_SHORT_B,
    SHORT_A_LONG_B,
    FLAT
}
