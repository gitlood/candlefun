package com.example.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class TradeResponseDto(
    val symbol: String,
    val orderId: Long,
    val clientOrderId: String,
    val transactTime: Long,
    val price: String,
    val origQty: String,
    val executedQty: String,
    val cummulativeQuoteQty: String,
    val status: String,
    val timeInForce: String,
    val type: String,
    val side: String
)
