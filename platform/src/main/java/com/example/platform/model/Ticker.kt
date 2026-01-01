package com.example.platform.model

data class Ticker(
    val symbol: String,
    val quoteVolume: Double,
    val tradeCount: Long,
    val lastPrice: Double
)
