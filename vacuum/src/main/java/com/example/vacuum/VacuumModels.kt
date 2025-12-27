package com.example.vacuum

data class VacuumSignal(
    val symbol: String,
    val timestampMs: Long,
    val depthNotional: Double,
    val depthDropPct: Double,
    val spreadPct: Double,
    val tradeCount: Int,
    val tradeImbalance: Double
)

data class VacuumOrderMeta(
    val orderId: Long,
    val symbol: String,
    val side: com.example.platform.model.enums.OrderSide,
    val expectedMid: Double?,
    val bestBid: Double?,
    val bestAsk: Double?,
    val timestampMs: Long
)
