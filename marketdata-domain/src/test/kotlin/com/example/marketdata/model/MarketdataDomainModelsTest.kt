package com.example.marketdata.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MarketdataDomainModelsTest {
    @Test
    fun `symbol validates format`() {
        assertFailsWith<IllegalArgumentException> { Symbol(" btcusdt ") }
        assertEquals(Symbol("BTCUSDT"), "btcusdt".asSymbol())
    }

    @Test
    fun `market state config validates positive values`() {
        assertFailsWith<IllegalArgumentException> {
            MarketStateConfig(depthLevels = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            MarketStateConfig(volWindows = emptyList())
        }
    }
}
