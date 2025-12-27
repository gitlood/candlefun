package com.example.survivor

import kotlin.test.Test
import kotlin.test.assertFailsWith

class SurvivorConfigTest {
    @Test
    fun `survivor config validates inputs`() {
        assertFailsWith<IllegalArgumentException> { SurvivorConfig(symbol = "BTCUSDT", orderQty = 0.0) }
        assertFailsWith<IllegalArgumentException> { SurvivorConfig(symbol = "BTCUSDT", entryFundingThreshold = 0.0) }
        assertFailsWith<IllegalArgumentException> { SurvivorConfig(symbol = "BTCUSDT", maxVolatility = 0.0) }
        assertFailsWith<IllegalArgumentException> { SurvivorConfig(symbol = "BTCUSDT", maxHoldMs = 0L) }
    }
}
