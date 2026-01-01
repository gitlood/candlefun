package com.example.network

import com.example.network.interfaces.ExchangeInfoService
import com.example.network.interfaces.TickerService
import com.example.network.universe.UniverseFetcher
import com.example.network.universe.UniverseFilter
import com.example.network.universe.UniverseRanker
import com.example.platform.model.MarketInfo
import com.example.platform.model.MarketSymbol
import com.example.platform.model.Ticker
import com.example.platform.model.UniverseConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class BinanceUniverseTest {

    @Test
    fun `fetchTopSymbols returns ranked symbols`() = runBlocking {
        val infoService = object : ExchangeInfoService {
            override suspend fun getExchangeInfo(): MarketInfo {
                return MarketInfo(
                    symbols = listOf(
                        MarketSymbol(
                            symbol = "BTCUSDT",
                            status = "TRADING",
                            quoteAsset = "USDT",
                            isSpotTradingAllowed = true,
                            permissions = listOf("SPOT")
                        ),
                        MarketSymbol(
                            symbol = "ETHUSDT",
                            status = "TRADING",
                            quoteAsset = "USDT",
                            isSpotTradingAllowed = true,
                            permissions = listOf("SPOT")
                        )
                    )
                )
            }
        }
        val tickerService = object : TickerService {
            override suspend fun getTickers24hr(): List<Ticker> {
                return listOf(
                    Ticker(symbol = "BTCUSDT", quoteVolume = 200.0, tradeCount = 10, lastPrice = 1.0),
                    Ticker(symbol = "ETHUSDT", quoteVolume = 100.0, tradeCount = 10, lastPrice = 1.0)
                )
            }
        }

        val universe = BinanceUniverse(
            fetcher = UniverseFetcher(infoService, tickerService),
            filter = UniverseFilter(),
            ranker = UniverseRanker()
        )

        val config = UniverseConfig(
            quoteAssets = setOf("USDT"),
            minQuoteVolume = 0.0,
            minTrades = 0,
            maxSymbols = 1
        )

        val result = universe.fetchTopSymbols(config)

        assertEquals(1, result.size)
        assertEquals("BTCUSDT", result[0].symbol)
    }
}
