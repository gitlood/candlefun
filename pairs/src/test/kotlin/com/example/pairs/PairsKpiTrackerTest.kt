package com.example.pairs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PairsKpiTrackerTest {
    @Test
    fun `kpi tracker updates pnl and half life`() {
        val config = PairsConfig(symbolA = "A", symbolB = "B", tailZ = 3.0)
        val kpi = PairsKpiTracker(config)
        kpi.onOpenSide(PairSide.LONG_A_SHORT_B)
        kpi.onEntry(timestampMs = 1_000L, spread = 1.0, z = 1.5)
        kpi.onFees(notional = 100.0, taker = true)
        kpi.onExit(timestampMs = 2_000L, spread = 0.5, notional = 10.0)

        val summary = kpi.summary()
        assertEquals(1_000L, summary.avgHalfLifeMs)
        assertTrue(summary.totalFees > 0.0)
        assertTrue(summary.netPnL != 0.0)
    }

    @Test
    fun `kpi tracker counts tail events`() {
        val config = PairsConfig(symbolA = "A", symbolB = "B", tailZ = 1.0)
        val kpi = PairsKpiTracker(config)
        kpi.onTailEvent(2.0)
        val summary = kpi.summary()
        assertEquals(1, summary.tailEvents)
    }
}
