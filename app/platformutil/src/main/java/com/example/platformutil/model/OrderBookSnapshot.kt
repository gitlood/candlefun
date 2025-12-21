package com.example.platformutil.model

data class OrderBookSnapshot(
    val timestamp: Long,
    val symbol: String,
    val bestBid: Double,
    val bestAsk: Double,
    val midPrice: Double,
    val spread: Double,
    val bidDepth10: Double,
    val askDepth10: Double,
    val imbalance10: Double,
    val bidDepth20: Double,
    val askDepth20: Double,
    val imbalance20: Double,
    val updateId: Long
)
