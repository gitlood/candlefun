package com.example.network.model

data class MarketInfo(
    val symbols: List<MarketSymbol> = emptyList()
)

data class MarketSymbol(
    val symbol: String,
    val status: String,
    val quoteAsset: String,
    val isSpotTradingAllowed: Boolean,
    val permissions: List<String>
)
