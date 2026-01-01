package com.example.platform.model

data class OrderBook(
    val lastUpdateId: Long,
    val bids: List<OrderBookEntry>,
    val asks: List<OrderBookEntry>
)

data class OrderBookEntry(
    val price: Double,
    val quantity: Double
)
