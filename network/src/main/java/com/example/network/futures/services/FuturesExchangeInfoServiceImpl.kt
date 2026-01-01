package com.example.network.futures.services

import com.example.network.config.BinanceEndpoints
import com.example.network.futures.dto.FuturesExchangeInfoDto
import com.example.network.futures.interfaces.FuturesExchangeInfoService
import com.example.network.futures.interfaces.FuturesSymbolFilters
import com.example.network.helper.getOrThrow
import io.ktor.client.HttpClient

internal class FuturesExchangeInfoServiceImpl(
    private val client: HttpClient,
    private val endpoints: BinanceEndpoints
) : FuturesExchangeInfoService {
    override suspend fun fetchSymbols(): Set<String> {
        val info: FuturesExchangeInfoDto =
            client.getOrThrow("${endpoints.restBase}/fapi/v1/exchangeInfo")
        return info.symbols
            .filter { it.status == "TRADING" }
            .map { it.symbol }
            .toSet()
    }

    override suspend fun fetchSymbolFilters(): Map<String, FuturesSymbolFilters> {
        val info: FuturesExchangeInfoDto =
            client.getOrThrow("${endpoints.restBase}/fapi/v1/exchangeInfo")
        return info.symbols
            .filter { it.status == "TRADING" }
            .mapNotNull { symbol ->
                val priceFilter = symbol.filters.firstOrNull { it.filterType == "PRICE_FILTER" }
                val lotFilter = symbol.filters.firstOrNull { it.filterType == "LOT_SIZE" }
                val notionalFilter = symbol.filters.firstOrNull { it.filterType == "MIN_NOTIONAL" }
                    ?: symbol.filters.firstOrNull { it.filterType == "NOTIONAL" }
                val tickSize = priceFilter?.tickSize?.toDoubleOrNull()
                val stepSize = lotFilter?.stepSize?.toDoubleOrNull()
                if (tickSize == null || stepSize == null) return@mapNotNull null
                val minQty = lotFilter.minQty?.toDoubleOrNull()
                val minNotional = notionalFilter?.minNotional?.toDoubleOrNull()
                    ?: notionalFilter?.notional?.toDoubleOrNull()
                symbol.symbol to FuturesSymbolFilters(
                    tickSize = tickSize,
                    stepSize = stepSize,
                    minQty = minQty,
                    minNotional = minNotional
                )
            }
            .toMap()
    }
}
