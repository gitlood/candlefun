package com.example.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WsKlineDto(
    @SerialName("t") val openTime: Long,
    @SerialName("T") val closeTime: Long,
    @SerialName("s") val symbol: String,
    @SerialName("i") val interval: String,
    @SerialName("f") val firstTradeId: Long,
    @SerialName("L") val lastTradeId: Long,
    @SerialName("o") val open: String,
    @SerialName("c") val close: String,
    @SerialName("h") val high: String,
    @SerialName("l") val low: String,
    @SerialName("v") val volume: String,
    @SerialName("n") val numberOfTrades: Int,
    @SerialName("x") val isClosed: Boolean,
    @SerialName("q") val quoteAssetVolume: String,
    @SerialName("V") val takerBuyBaseAssetVolume: String,
    @SerialName("Q") val takerBuyQuoteAssetVolume: String,
    @SerialName("B") val ignore: String
)
