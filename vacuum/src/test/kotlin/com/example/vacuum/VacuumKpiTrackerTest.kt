package com.example.vacuum

import com.example.account.domain.Price
import com.example.account.domain.Qty
import com.example.account.domain.Symbol
import com.example.execution.impl.FillRecord
import com.example.platform.model.enums.OrderSide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VacuumKpiTrackerTest {
    @Test
    fun `kpi tracker records slippage and adverse moves`() {
        val config = VacuumConfig(symbol = "BTCUSDT", slippagePauseBps = 5.0, tailLossBps = 8.0)
        val tracker = VacuumKpiTracker(config)
        val meta = VacuumOrderMeta(
            orderId = 1L,
            symbol = "BTCUSDT",
            side = OrderSide.BUY,
            expectedMid = 100.0,
            bestBid = 99.0,
            bestAsk = 101.0,
            timestampMs = 1000L
        )
        tracker.onOrderPlaced(meta)
        tracker.onFill(
            FillRecord(
                orderId = 1L,
                symbol = Symbol.of("BTCUSDT"),
                side = OrderSide.BUY,
                price = Price.fromDouble(102.0),
                quantity = Qty.fromDouble(1.0),
                fillTimeMs = 1000L
            )
        )
        tracker.onMarketState("BTCUSDT", 99.0, 3000L)

        val summary = tracker.summary()
        assertNotNull(summary.avgSlippageBps)
        assertNotNull(summary.avgAdverseMoveBps)
        assertTrue(summary.tailLossCount >= 1)
        assertEquals(summary.lastSlippageBps, tracker.lastSlippageBps())
    }
}
