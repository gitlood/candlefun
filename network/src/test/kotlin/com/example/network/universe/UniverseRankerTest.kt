package com.example.network.universe

import com.example.platform.model.Ticker
import com.example.platform.model.UniverseConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class UniverseRankerTest {

    private val ranker = UniverseRanker()

    @Test
    fun `rank sorts by quote volume descending and respects limits`() {
        val config = UniverseConfig(
            quoteAssets = setOf("USDT"),
            minQuoteVolume = 100.0,
            minTrades = 10,
            maxSymbols = 3
        )

        // Only these are "allowed" by previous filter step
        val allowed = setOf("A", "B", "C", "D", "E")

        val tickers = listOf(
            createTicker("A", 1000.0, 100), // 1st
            createTicker("B", 500.0, 50),   // 2nd
            createTicker("C", 200.0, 20),   // 3rd
            createTicker("D", 100.0, 10),   // 4th (should be cut by maxSymbols)
            createTicker("E", 50.0, 5)      // 5th (should be cut by minQuoteVolume)
        )

        val result = ranker.rank(tickers, allowed, config)

        assertEquals(3, result.size)
        assertEquals("A", result[0].symbol)
        assertEquals("B", result[1].symbol)
        assertEquals("C", result[2].symbol)
    }

    @Test
    fun `rank filters out unallowed symbols`() {
        val config = UniverseConfig(maxSymbols = 10)
        val allowed = setOf("A")
        val tickers = listOf(
            createTicker("A", 1000.0),
            createTicker("B", 2000.0) // Not allowed
        )

        val result = ranker.rank(tickers, allowed, config)
        assertEquals(1, result.size)
        assertEquals("A", result[0].symbol)
    }

    private fun createTicker(symbol: String, quoteVol: Double, trades: Long = 100) = Ticker(
        symbol = symbol,
        quoteVolume = quoteVol,
        tradeCount = trades,
        lastPrice = 100.0
    )
}
