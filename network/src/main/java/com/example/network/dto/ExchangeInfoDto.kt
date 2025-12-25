package com.example.network.dto

import kotlinx.serialization.Serializable

@Serializable
data class ExchangeInfoDto(
    val symbols: List<ExchangeSymbolDto> = emptyList()
)

@Serializable
data class ExchangeSymbolDto(
    val symbol: String,
    val status: String? = null,
    val quoteAsset: String? = null,
    val isSpotTradingAllowed: Boolean? = null,
    val permissions: List<String>? = null
)
