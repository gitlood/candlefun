package com.example.network.model

data class OrderBookDepth(
    val lastUpdateId: Long,
    val bids: List<OrderBookLevel>,
    val asks: List<OrderBookLevel>
)
