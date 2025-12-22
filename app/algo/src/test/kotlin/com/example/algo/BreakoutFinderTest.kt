package com.example.algo

import com.example.algo.profitgroups.BreakoutFinder
import com.example.platformutil.ProfitGroupMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BreakoutFinderTest {
    @Test
    fun breakoutFinder_findsGroupWhenThresholdHit() {
        val candles = listOf(
            candle(0L, open = 1.0, high = 1.0, low = 1.0, close = 1.0),
            candle(60_000L, open = 1.0, high = 1.06, low = 1.0, close = 1.05),
            candle(120_000L, open = 1.05, high = 1.1, low = 1.0, close = 1.08),
            candle(180_000L, open = 1.08, high = 1.08, low = 1.0, close = 1.07)
        )
        val finder = BreakoutFinder(candles)
        val groups = finder.findGroups(
            horizonBars = 2,
            thresholdsPct = doubleArrayOf(0.05),
            localLowLookbackBars = 0,
            requireContinuous = true,
            intervalMillis = 60_000L,
            maxDrawdownPctAllowed = 0.2,
            stopLossPct = 0.2,
            worstCaseIfBothHit = true,
            mode = ProfitGroupMode.TP_HIT,
            takeProfitPct = 0.05,
            feePerSide = 0.0,
            slippagePerSide = 0.0
        )
        assertEquals(1, groups.size)
        assertTrue(groups.first().gainPctToPeakHigh >= 0.05)
    }

    @Test
    fun breakoutFinder_netPositiveAcceptsWithoutThreshold() {
        val candles = listOf(
            candle(0L, open = 1.0, high = 1.0, low = 1.0, close = 1.0),
            candle(60_000L, open = 1.0, high = 1.02, low = 0.99, close = 1.01),
            candle(120_000L, open = 1.01, high = 1.01, low = 1.0, close = 1.01),
            candle(180_000L, open = 1.01, high = 1.01, low = 1.0, close = 1.01)
        )
        val finder = BreakoutFinder(candles)
        val groups = finder.findGroups(
            horizonBars = 2,
            thresholdsPct = doubleArrayOf(0.05),
            localLowLookbackBars = 0,
            requireContinuous = true,
            intervalMillis = 60_000L,
            maxDrawdownPctAllowed = 0.2,
            stopLossPct = 0.2,
            worstCaseIfBothHit = true,
            mode = ProfitGroupMode.NET_POSITIVE,
            takeProfitPct = 0.05,
            feePerSide = 0.0,
            slippagePerSide = 0.0
        )
        assertEquals(1, groups.size)
    }

    @Test
    fun breakoutFinder_dedupeKeepsHighestGain() {
        val base = breakoutGroup(entryOpenTime = 1L, windowEnd = 5L, gain = 0.05)
        val better = breakoutGroup(entryOpenTime = 3L, windowEnd = 6L, gain = 0.1)
        val deduped = BreakoutFinder(emptyList()).dedupeOverlaps(listOf(base, better))
        assertEquals(1, deduped.size)
        assertEquals(0.1, deduped.first().gainPctToPeakHigh)
    }

}
