package com.example.network.universe

import com.example.network.interfaces.ExchangeInfoService
import com.example.network.interfaces.TickerService
import com.example.platform.model.MarketInfo
import com.example.platform.model.MarketSymbol
import com.example.platform.model.Ticker
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class UniverseFetcherTest {

    @Test
    fun `fetchData returns exchange info and tickers`() = runBlocking {
        val info = MarketInfo(
            symbols = listOf(
                MarketSymbol(
                    symbol = "BTCUSDT",
                    status = "TRADING",
                    quoteAsset = "USDT",
                    isSpotTradingAllowed = true,
                    permissions = listOf("SPOT")
                )
            )
        )
        val tickers = listOf(
            Ticker(symbol = "BTCUSDT", quoteVolume = 100.0, tradeCount = 5, lastPrice = 1.0)
        )

        val exchangeInfoService = object : ExchangeInfoService {
            override suspend fun getExchangeInfo(): MarketInfo = info
        }
        val tickerService = object : TickerService {
            override suspend fun getTickers24hr(): List<Ticker> = tickers
        }

        val fetcher = UniverseFetcher(exchangeInfoService, tickerService)

        val result = fetcher.fetchData()

        assertEquals(info, result.first)
        assertEquals(tickers, result.second)
    }
}
