package com.example.historicaldata.util

import com.example.network.model.OrderBookDepth
import com.example.network.model.OrderBookLevel
import kotlin.test.Test
import kotlin.test.assertEquals

class OrderBookFeatureCalculatorTest {
    @Test
    fun buildSnapshot_computesDepthAndImbalance() {
        val depth = OrderBookDepth(
            lastUpdateId = 123L,
            bids = listOf(
                OrderBookLevel(price = 100.0, quantity = 2.0),
                OrderBookLevel(price = 99.5, quantity = 1.0)
            ),
            asks = listOf(
                OrderBookLevel(price = 101.0, quantity = 1.0),
                OrderBookLevel(price = 101.5, quantity = 1.0)
            )
        )

        val snapshot = OrderBookFeatureCalculator.buildSnapshot("ETHUSDT", depth, 1_000L)

        assertEquals(100.0, snapshot.bestBid)
        assertEquals(101.0, snapshot.bestAsk)
        assertEquals(100.5, snapshot.midPrice)
        assertEquals(1.0, snapshot.spread)
        assertEquals(3.0, snapshot.bidDepth10)
        assertEquals(2.0, snapshot.askDepth10)
        assertEquals(0.2, snapshot.imbalance10)
        assertEquals(123L, snapshot.updateId)
    }
}
