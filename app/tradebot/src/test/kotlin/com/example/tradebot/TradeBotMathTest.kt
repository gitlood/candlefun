package com.example.tradebot

import com.example.platformutil.model.Candle
import kotlin.test.Test
import kotlin.test.assertEquals

class TradeBotMathTest {
    @Test
    fun barsFromMinutes_roundsUpToAtLeastOne() {
        assertEquals(1, TradeBotMath.barsFromMinutes(1, 300_000L))
        assertEquals(12, TradeBotMath.barsFromMinutes(60, 300_000L))
    }

    @Test
    fun inferIntervalMillis_usesMedianDiff() {
        val candles = listOf(
            candleAt(0L),
            candleAt(60_000L),
            candleAt(120_000L),
            candleAt(180_000L)
        )
        assertEquals(60_000L, TradeBotMath.inferIntervalMillis(candles))
    }

    private fun candleAt(openTime: Long): Candle {
        return Candle(
            openTime = openTime,
            open = "1",
            high = "2",
            low = "0.5",
            close = "1.5",
            volume = "10",
            closeTime = openTime + 60_000L
        )
    }
}
