package com.example.network.model

data class SymbolLiquidity(
    val symbol: String,
    val quoteVolume: Double,
    val trades: Int
)
