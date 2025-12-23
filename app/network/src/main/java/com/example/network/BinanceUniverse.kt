package com.example.network

import com.example.network.dto.ExchangeInfoDto
import com.example.network.dto.Ticker24HrDto
import com.example.network.helper.NetworkConstants.BASE_URL
import com.example.network.helper.getOrThrow
import com.example.network.model.SymbolLiquidity
import com.example.network.model.UniverseConfig
import io.ktor.client.HttpClient

class BinanceUniverse(
    private val httpClient: HttpClient
) {

    suspend fun fetchTopSymbols(config: UniverseConfig): List<SymbolLiquidity> {
        val exchangeInfo = httpClient.getOrThrow<ExchangeInfoDto>("$BASE_URL/exchangeInfo")
        val tickers = httpClient.getOrThrow<List<Ticker24HrDto>>("$BASE_URL/ticker/24hr")

        val exchangeAllowed = exchangeInfo.symbols
            .filter { symbol ->
                val statusOk = symbol.status == null || symbol.status == "TRADING"
                val quoteOk = symbol.quoteAsset?.uppercase() in config.quoteAssets
                val permissions = symbol.permissions
                val spotOk = symbol.isSpotTradingAllowed != false &&
                    (permissions.isNullOrEmpty() || permissions.contains("SPOT"))
                statusOk && quoteOk && spotOk
            }
            .map { it.symbol }
            .toSet()

        val allowed = exchangeAllowed.ifEmpty {
            val quoteAssets = config.quoteAssets
            tickers.map { it.symbol }
                .filter { symbol ->
                    if (quoteAssets.isEmpty()) return@filter true
                    quoteAssets.any { asset -> symbol.endsWith(asset) }
                }
                .toSet()
        }

        val include = config.includeSymbols
        val exclude = config.excludeSymbols

        val ranked = tickers
            .asSequence()
            .filter { it.symbol in allowed }
            .filter { include.isEmpty() || it.symbol in include }
            .filter { exclude.isEmpty() || it.symbol !in exclude }
            .map {
                SymbolLiquidity(
                    symbol = it.symbol,
                    quoteVolume = it.quoteVolume.toDoubleOrNull() ?: 0.0,
                    trades = it.count.toInt()
                )
            }
            .filter { it.quoteVolume >= config.minQuoteVolume }
            .filter { it.trades >= config.minTrades }
            .sortedByDescending { it.quoteVolume }
            .toList()

        return if (config.maxSymbols > 0) ranked.take(config.maxSymbols) else ranked
    }
}
