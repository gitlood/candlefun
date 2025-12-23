package com.example.network.model

import kotlinx.serialization.Serializable

@Serializable
data class ExchangeInfo(
    val symbols: List<ExchangeSymbol> = emptyList()
)

@Serializable
data class ExchangeSymbol(
    val symbol: String,
    val status: String? = null,
    val quoteAsset: String? = null,
    val isSpotTradingAllowed: Boolean? = null,
    val permissions: List<String>? = null
)
