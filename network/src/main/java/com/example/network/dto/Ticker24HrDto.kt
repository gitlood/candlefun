package com.example.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Ticker24HrDto(
    val symbol: String,
    @SerialName("quoteVolume")
    val quoteVolume: String, // API returns string usually, we parse to double in mapper
    @SerialName("count")
    val count: Long,
    val lastPrice: String
)
