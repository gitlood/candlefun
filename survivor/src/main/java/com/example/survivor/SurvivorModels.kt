package com.example.survivor

data class SurvivorSnapshot(
    val symbol: String,
    val timestampMs: Long,
    val fundingRate: Double,
    val nextFundingTimeMs: Long,
    val markPrice: Double,
    val indexPrice: Double,
    val spreadPct: Double,
    val volatility: Double,
    val openInterest: Double
) {
    val basisPct: Double
        get() = if (indexPrice > 0.0) (markPrice - indexPrice) / indexPrice else 0.0
}

enum class SurvivorSide {
    LONG_PERP,
    SHORT_PERP,
    FLAT
}
