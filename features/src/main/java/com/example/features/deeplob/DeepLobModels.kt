package com.example.features.deeplob

import com.example.platform.model.MarketState

data class DeepLobSample(
    val symbol: String,
    val endTimeMs: Long,
    val window: List<MarketState>
)

data class DeepLobConfig(
    val windowSize: Int = 100,
    val minEvents: Int = 50
)
