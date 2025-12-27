package com.example.network.futures.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FuturesPremiumIndexDto(
    @SerialName("symbol") val symbol: String,
    @SerialName("markPrice") val markPrice: String,
    @SerialName("indexPrice") val indexPrice: String,
    @SerialName("lastFundingRate") val lastFundingRate: String,
    @SerialName("nextFundingTime") val nextFundingTime: Long
)
