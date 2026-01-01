package com.example.platform.model

data class SymbolLiquidity(
    val symbol: String,
    val quoteVolume: Double,
    val trades: Long
)
