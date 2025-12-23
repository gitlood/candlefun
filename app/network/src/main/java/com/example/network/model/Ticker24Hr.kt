package com.example.network.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Ticker24Hr(
    val symbol: String,
    @SerialName("quoteVolume")
    val quoteVolume: Double,
    @SerialName("count")
    val tradeCount: Int,
    val lastPrice: Double
)
