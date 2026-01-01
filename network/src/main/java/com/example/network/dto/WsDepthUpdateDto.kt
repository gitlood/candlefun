package com.example.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WsDepthUpdateDto(
    @SerialName("s") val symbol: String,
    @SerialName("E") val eventTime: Long? = null,
    @SerialName("U") val firstUpdateId: Long,
    @SerialName("u") val finalUpdateId: Long,
    // bids/asks are arrays like [["price","qty"], ...]
    @SerialName("b") val bids: List<List<String>>,
    @SerialName("a") val asks: List<List<String>>
)
