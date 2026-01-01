package com.example.network.dto

import com.example.network.serializer.KlineSerializer
import kotlinx.serialization.Serializable

@Serializable(with = KlineSerializer::class)
data class KlineDto(
    val openTime: Long,
    val open: String,
    val high: String,
    val low: String,
    val close: String,
    val volume: String,
    val closeTime: Long,
    val quoteAssetVolume: String,
    val numberOfTrades: Int,
    val takerBuyBaseAssetVolume: String,
    val takerBuyQuoteAssetVolume: String,
    val ignore: String
)
