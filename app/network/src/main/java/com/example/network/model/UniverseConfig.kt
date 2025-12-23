package com.example.network.model

data class UniverseConfig(
    val quoteAssets: Set<String> = setOf("USDT"),
    val minQuoteVolume: Double = 0.0,
    val minTrades: Int = 0,
    val maxSymbols: Int = 10,
    val includeSymbols: Set<String> = emptySet(),
    val excludeSymbols: Set<String> = emptySet()
)
