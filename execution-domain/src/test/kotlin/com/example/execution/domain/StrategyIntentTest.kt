package com.example.execution.domain

import com.example.account.domain.Qty
import com.example.account.domain.Symbol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StrategyIntentTest {

    @Test
    fun `requires non-blank strategy id`() {
        assertFailsWith<IllegalArgumentException> {
            StrategyIntent(strategyId = "", symbol = Symbol.of("BTCUSDT"), desiredDelta = Qty.ZERO)
        }
    }

    @Test
    fun `confidence weight clamps`() {
        assertEquals(0.0, confidenceWeight(-1.0, 1.0))
        assertEquals(1.0, confidenceWeight(2.0, 1.0))
        assertEquals(0.25, confidenceWeight(0.5, 2.0))
    }
}
