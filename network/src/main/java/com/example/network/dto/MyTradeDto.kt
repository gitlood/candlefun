package com.example.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MyTradeDto(
    @SerialName("symbol") val symbol: String,
    @SerialName("id") val tradeId: Long,
    @SerialName("orderId") val orderId: Long,
    @SerialName("price") val price: String,
    @SerialName("qty") val quantity: String,
    @SerialName("quoteQty") val quoteQty: String,
    @SerialName("commission") val commission: String,
    @SerialName("commissionAsset") val commissionAsset: String,
    @SerialName("time") val time: Long,
    @SerialName("isBuyer") val isBuyer: Boolean,
    @SerialName("isMaker") val isMaker: Boolean,
    @SerialName("isBestMatch") val isBestMatch: Boolean
)
