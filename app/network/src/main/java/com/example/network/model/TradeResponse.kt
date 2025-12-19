package com.example.network.model

import kotlinx.serialization.Serializable

@Serializable
data class TradeResponse(
    val symbol: String,
    val orderId: Long,
    val clientOrderId: String,
    val transactTime: Long,
    val price: String? = null,
    val origQty: String? = null,
    val executedQty: String? = null,
    val cummulativeQuoteQty: String? = null,
    val status: String? = null,
    val timeInForce: String? = null,
    val type: String? = null,
    val side: String? = null
)
