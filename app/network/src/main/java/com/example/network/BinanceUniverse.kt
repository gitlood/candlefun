package com.example.network

import com.example.network.client.client
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

data class SymbolLiquidity(
    val symbol: String,
    val quoteVolume: Double,
    val trades: Int
)

data class UniverseConfig(
    val quoteAssets: Set<String> = setOf("USDT"),
    val minQuoteVolume: Double = 0.0,
    val minTrades: Int = 0,
    val maxSymbols: Int = 10,
    val includeSymbols: Set<String> = emptySet(),
    val excludeSymbols: Set<String> = emptySet()
)

class BinanceUniverse(
    private val httpClient: HttpClient = client
) {
    private val baseUrl = "https://api.binance.com/api/v3"

    suspend fun fetchTopSymbols(config: UniverseConfig): List<SymbolLiquidity> {
        val exchangeInfo: ExchangeInfo = httpClient.get("$baseUrl/exchangeInfo").body()
        val tickers: List<Ticker24h> = httpClient.get("$baseUrl/ticker/24hr").body()

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
                    quoteVolume = it.quoteVolume?.toDoubleOrNull() ?: 0.0,
                    trades = it.trades ?: 0
                )
            }
            .filter { it.quoteVolume >= config.minQuoteVolume }
            .filter { it.trades >= config.minTrades }
            .sortedByDescending { it.quoteVolume }
            .toList()

        return if (config.maxSymbols > 0) ranked.take(config.maxSymbols) else ranked
    }

    @Serializable
    private data class ExchangeInfo(
        val symbols: List<ExchangeSymbol> = emptyList()
    )

    @Serializable
    private data class ExchangeSymbol(
        val symbol: String,
        val status: String? = null,
        val quoteAsset: String? = null,
        val isSpotTradingAllowed: Boolean? = null,
        val permissions: List<String>? = null
    )

    @Serializable
    private data class Ticker24h(
        val symbol: String,
        @SerialName("quoteVolume")
        val quoteVolume: String? = null,
        @SerialName("count")
        val trades: Int? = null
    )
}
