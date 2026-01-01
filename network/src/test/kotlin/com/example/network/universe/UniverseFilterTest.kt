package com.example.network.universe

import com.example.platform.model.MarketInfo
import com.example.platform.model.MarketSymbol
import com.example.platform.model.UniverseConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class UniverseFilterTest {

    private val filter = UniverseFilter()

    @Test
    fun `filterAllowed only returns valid spot trading pairs`() {
        val config = UniverseConfig(
            quoteAssets = setOf("USDT"),
            minQuoteVolume = 0.0,
            minTrades = 0,
            maxSymbols = 100
        )

        val symbols = listOf(
            // 1. Valid
            createSymbol("BTCUSDT", "TRADING", "USDT", true, listOf("SPOT")),
            // 2. Wrong quote asset
            createSymbol("ETHBTC", "TRADING", "BTC", true, listOf("SPOT")),
            // 3. Not trading
            createSymbol("SOLUSDT", "BREAK", "USDT", true, listOf("SPOT")),
            // 4. Spot not allowed
            createSymbol("XRPUSDT", "TRADING", "USDT", false, listOf("SPOT")),
            // 5. Missing SPOT permission
            createSymbol("ADAUSDT", "TRADING", "USDT", true, listOf("MARGIN"))
        )
        // MarketInfo takes List<MarketSymbol>
        val marketInfo = MarketInfo(symbols)

        val result = filter.filterAllowed(marketInfo, config)

        assertEquals(1, result.size)
        assertEquals(setOf("BTCUSDT"), result)
    }

    private fun createSymbol(
        symbol: String,
        status: String,
        quoteAsset: String,
        isSpotAllowed: Boolean,
        permissions: List<String>
    ) = MarketSymbol(
        symbol = symbol,
        status = status,
        quoteAsset = quoteAsset,
        isSpotTradingAllowed = isSpotAllowed,
        permissions = permissions
    )
}
