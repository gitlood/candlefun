package com.example.platformutil.model

data class Candle(
    val openTime: Long,
    val open: String,
    val high: String,
    val low: String,
    val close: String,
    val volume: String,
    val closeTime: Long,
    val quoteAssetVolume: String = "0",
    val numberOfTrades: Int = 0,
    val takerBuyBaseAssetVolume: String = "0",
    val takerBuyQuoteAssetVolume: String = "0"
)