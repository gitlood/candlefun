package com.example.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AggTradeDto(
    @SerialName("a") val tradeId: Long,
    @SerialName("p") val price: String,
    @SerialName("q") val quantity: String,
    @SerialName("T") val timestamp: Long,
    @SerialName("m") val isBuyerMaker: Boolean
)
