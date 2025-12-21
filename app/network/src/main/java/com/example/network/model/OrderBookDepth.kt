package com.example.network.model

data class OrderBookLevel(
    val price: Double,
    val quantity: Double
)

data class OrderBookDepth(
    val lastUpdateId: Long,
    val bids: List<OrderBookLevel>,
    val asks: List<OrderBookLevel>
)
