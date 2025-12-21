package com.example.platformutil

import kotlin.test.Test
import kotlin.test.assertEquals

class MarketDataPathsTest {
    @Test
    fun dbPathHelpersRespectLegacyWhenPreferredMissing() {
        val preferred = java.io.File(candleDbPath(BINANCE_SYMBOL, "5m"))
        val legacy = java.io.File("binance.db")

        val expected = when {
            preferred.exists() -> preferred.name
            legacy.exists() -> legacy.name
            else -> preferred.name
        }

        val resolved = resolveCandleDbPath(BINANCE_SYMBOL, "5m")
        assertEquals(expected, resolved)

        assertEquals("binance_BTCUSDT_1m.db", candleDbPath("BTCUSDT", "1m"))
        assertEquals("orderbook_ETHUSDT.db", orderBookDbPath("ethusdt"))
    }
}
