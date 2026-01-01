package com.example.network.futures.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FuturesLeverageDto(
    @SerialName("symbol") val symbol: String,
    @SerialName("leverage") val leverage: Int,
    @SerialName("maxNotionalValue") val maxNotionalValue: String
)
