package com.example.orderbookscalper

import com.example.network.model.OrderBookLevel
import kotlin.test.Test
import kotlin.test.assertTrue

class ScalperMathTest {
    @Test
    fun computeMicroPrice_matchesFormula() {
        val micro = computeMicroPrice(
            bestBid = 100.0,
            bestAsk = 101.0,
            bidQty1 = 2.0,
            askQty1 = 1.0
        )
        assertTrue(micro != null && micro > 100.6 && micro < 100.7)
    }

    @Test
    fun computeImbalance_usesDepthLevels() {
        val bids = listOf(
            OrderBookLevel(price = 100.0, quantity = 5.0),
            OrderBookLevel(price = 99.5, quantity = 3.0)
        )
        val asks = listOf(
            OrderBookLevel(price = 100.5, quantity = 2.0),
            OrderBookLevel(price = 101.0, quantity = 2.0)
        )
        val imbalance = computeImbalance(bids, asks, depth = 2)
        assertTrue(imbalance > 0.32 && imbalance < 0.34)
    }

    @Test
    fun computeVolProxyPct_returnsPositiveForMoves() {
        val samples = listOf(
            PriceSample(timestamp = 1L, mid = 100.0),
            PriceSample(timestamp = 2L, mid = 101.0),
            PriceSample(timestamp = 3L, mid = 102.0)
        )
        val vol = computeVolProxyPct(samples)
        assertTrue(vol > 0.0)
    }
}
