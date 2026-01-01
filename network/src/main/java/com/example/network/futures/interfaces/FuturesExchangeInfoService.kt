package com.example.network.futures.interfaces

data class FuturesSymbolFilters(
    val tickSize: Double,
    val stepSize: Double,
    val minQty: Double? = null,
    val minNotional: Double? = null
)

interface FuturesExchangeInfoService {
    suspend fun fetchSymbols(): Set<String>
    suspend fun fetchSymbolFilters(): Map<String, FuturesSymbolFilters>
}
