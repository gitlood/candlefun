package com.example.platformutil

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MarketDataTest {
    @Test
    fun intervalToMillis_roundTrip() {
        val interval = "5m"
        val millis = intervalToMillis(interval)
        val back = intervalMillisToBinance(millis)
        assertEquals(interval, back)
    }

    @Test
    fun intervalToMillis_rejectsUnknown() {
        assertFailsWith<IllegalStateException> {
            intervalToMillis("7m")
        }
    }

    @Test
    fun resolveCandleDbPath_prefersSymbolInterval() {
        val path = resolveCandleDbPath("FOO", "5m")
        assertEquals("binance_FOO_5m.db", path)
    }
}
