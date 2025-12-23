package com.example.network.model

data class OrderResponse(
    val symbol: String,
    val orderId: Long,
    val clientOrderId: String,
    val transactTime: Long,
    val price: Double,
    val origQty: Double,
    val executedQty: Double,
    val cummulativeQuoteQty: Double,
    val status: String,
    val timeInForce: String,
    val type: String,
    val side: String
)
