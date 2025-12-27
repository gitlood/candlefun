package com.example.network.futures.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FuturesOpenInterestDto(
    @SerialName("symbol") val symbol: String,
    @SerialName("openInterest") val openInterest: String,
    @SerialName("time") val time: Long
)
