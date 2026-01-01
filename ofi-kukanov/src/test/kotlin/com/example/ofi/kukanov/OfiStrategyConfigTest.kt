package com.example.ofi.kukanov

import kotlin.test.Test
import kotlin.test.assertFailsWith

class OfiStrategyConfigTest {
    @Test
    fun `ofi config validates inputs`() {
        assertFailsWith<IllegalArgumentException> { OfiStrategyConfig(symbol = "BTCUSDT", orderQty = 0.0) }
        assertFailsWith<IllegalArgumentException> { OfiStrategyConfig(symbol = "BTCUSDT", entryThreshold = 0.0) }
        assertFailsWith<IllegalArgumentException> { OfiStrategyConfig(symbol = "BTCUSDT", maxHoldMs = 0L) }
        assertFailsWith<IllegalArgumentException> { OfiStrategyConfig(symbol = "BTCUSDT", joinOffsetTicks = -1) }
    }
}
