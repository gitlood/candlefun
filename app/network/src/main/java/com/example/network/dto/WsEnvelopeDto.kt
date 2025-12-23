package com.example.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WsEnvelopeDto(
    val data: WsDataDto
)

@Serializable
data class WsDataDto(
    @SerialName("s") val symbol: String,
    @SerialName("k") val kline: WsKlineDto
)

@Serializable
data class WsKlineDto(
    @SerialName("t") val openTime: Long,
    @SerialName("o") val open: String,
    @SerialName("h") val high: String,
    @SerialName("l") val low: String,
    @SerialName("c") val close: String,
    @SerialName("x") val isClosed: Boolean
)
