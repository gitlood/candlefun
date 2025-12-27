package com.example.vacuum

import kotlin.test.Test
import kotlin.test.assertFailsWith

class VacuumConfigTest {
    @Test
    fun `vacuum config validates inputs`() {
        assertFailsWith<IllegalArgumentException> { VacuumConfig(symbol = "BTCUSDT", depthLevels = 0) }
        assertFailsWith<IllegalArgumentException> { VacuumConfig(symbol = "BTCUSDT", depthWindowMs = 0L) }
        assertFailsWith<IllegalArgumentException> { VacuumConfig(symbol = "BTCUSDT", orderQty = 0.0) }
        assertFailsWith<IllegalArgumentException> { VacuumConfig(symbol = "BTCUSDT", priceTick = 0.0) }
        assertFailsWith<IllegalArgumentException> { VacuumConfig(symbol = "BTCUSDT", maxHoldMs = 0L) }
    }
}
