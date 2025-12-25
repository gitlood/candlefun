package com.example.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WsBookTickerDto(
    @SerialName("s") val symbol: String,
    @SerialName("E") val eventTime: Long? = null,
    @SerialName("u") val updateId: Long? = null,
    @SerialName("b") val bestBidPrice: String,
    @SerialName("B") val bestBidQty: String,
    @SerialName("a") val bestAskPrice: String,
    @SerialName("A") val bestAskQty: String
)
