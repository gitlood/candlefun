package com.example.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OrderDto(
    @SerialName("symbol") val symbol: String,
    @SerialName("orderId") val orderId: Long,
    @SerialName("clientOrderId") val clientOrderId: String,
    @SerialName("price") val price: String,
    @SerialName("origQty") val origQty: String,
    @SerialName("executedQty") val executedQty: String,
    @SerialName("cummulativeQuoteQty") val cummulativeQuoteQty: String,
    @SerialName("status") val status: String,
    @SerialName("timeInForce") val timeInForce: String,
    @SerialName("type") val type: String,
    @SerialName("side") val side: String,
    @SerialName("time") val time: Long? = null,
    @SerialName("transactTime") val transactTime: Long? = null
)
