package com.example.network.model

data class Kline(
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val closeTime: Long,
    val quoteAssetVolume: Double,
    val numberOfTrades: Int,
    val takerBuyBaseAssetVolume: Double,
    val takerBuyQuoteAssetVolume: Double
)
