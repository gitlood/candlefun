package com.example.network.futures.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FuturesTradeDto(
    @SerialName("symbol") val symbol: String,
    @SerialName("id") val tradeId: Long,
    @SerialName("orderId") val orderId: Long,
    @SerialName("price") val price: String,
    @SerialName("qty") val quantity: String,
    @SerialName("quoteQty") val quoteQty: String,
    @SerialName("time") val time: Long,
    @SerialName("buyer") val buyer: Boolean,
    @SerialName("maker") val maker: Boolean
)
