package com.example.platform.model

data class Trade(
    val tradeId: Long,
    val price: Double,
    val quantity: Double,
    val timestamp: Long,
    val isBuyerMaker: Boolean
)
