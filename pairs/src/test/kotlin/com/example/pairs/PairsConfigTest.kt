package com.example.pairs

import kotlin.test.Test
import kotlin.test.assertFailsWith

class PairsConfigTest {
    @Test
    fun `pairs config validates inputs`() {
        assertFailsWith<IllegalArgumentException> { PairsConfig(symbolA = "A", symbolB = "B", minSamples = 1) }
        assertFailsWith<IllegalArgumentException> { PairsConfig(symbolA = "A", symbolB = "B", entryZ = 0.0) }
        assertFailsWith<IllegalArgumentException> { PairsConfig(symbolA = "A", symbolB = "B", maxHoldMs = 0L) }
        assertFailsWith<IllegalArgumentException> { PairsConfig(symbolA = "A", symbolB = "B", priceTick = 0.0) }
        assertFailsWith<IllegalArgumentException> { PairsConfig(symbolA = "A", symbolB = "B", tailZ = 0.0) }
    }
}
