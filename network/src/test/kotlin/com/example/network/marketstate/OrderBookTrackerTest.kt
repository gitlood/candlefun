package com.example.network.marketstate

import com.example.network.dto.WsDepthUpdateData
import com.example.platform.model.OrderBook
import com.example.platform.model.OrderBookEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderBookTrackerTest {

    @Test
    fun `loadSnapshot filters zero qty and exposes best levels`() {
        val tracker = OrderBookTracker()
        tracker.loadSnapshot(
            OrderBook(
                lastUpdateId = 10,
                bids = listOf(
                    OrderBookEntry(price = 100.0, quantity = 0.0),
                    OrderBookEntry(price = 99.0, quantity = 1.0)
                ),
                asks = listOf(
                    OrderBookEntry(price = 101.0, quantity = 2.0),
                    OrderBookEntry(price = 102.0, quantity = 0.0)
                )
            )
        )

        assertEquals(10L, tracker.lastUpdateId)
        assertNotNull(tracker.bestBid())
        assertNotNull(tracker.bestAsk())
        assertEquals(99.0, tracker.bestBid()!!.price, 0.0)
        assertEquals(101.0, tracker.bestAsk()!!.price, 0.0)
        assertEquals(1, tracker.topBids(1).size)
        assertEquals(1, tracker.topAsks(1).size)
    }

    @Test
    fun `applyUpdate returns false when no snapshot loaded`() {
        val tracker = OrderBookTracker()
        val update = WsDepthUpdateData(
            symbol = "BTCUSDT",
            firstUpdateId = 1,
            finalUpdateId = 1,
            bids = listOf(listOf("100.0", "1.0")),
            asks = listOf(listOf("101.0", "2.0"))
        )

        val result = tracker.applyUpdate(update)

        assertFalse(result.ok)
        assertEquals(0.0, result.ofiDelta, 0.0)
    }

    @Test
    fun `applyUpdate updates book and computes ofi`() {
        val tracker = OrderBookTracker()
        tracker.loadSnapshot(
            OrderBook(
                lastUpdateId = 10,
                bids = listOf(OrderBookEntry(price = 100.0, quantity = 5.0)),
                asks = listOf(OrderBookEntry(price = 101.0, quantity = 4.0))
            )
        )

        val update = WsDepthUpdateData(
            symbol = "BTCUSDT",
            firstUpdateId = 11,
            finalUpdateId = 11,
            bids = listOf(listOf("102.0", "3.0"), listOf("100.0", "0.0")),
            asks = listOf(listOf("101.0", "4.0"))
        )

        val result = tracker.applyUpdate(update)

        assertTrue(result.ok)
        assertEquals(11L, tracker.lastUpdateId)
        assertNotNull(tracker.bestBid())
        assertEquals(102.0, tracker.bestBid()!!.price, 0.0)
        assertEquals(1, tracker.topBids(10).size)
        assertEquals(3.0, result.ofiDelta, 0.0)
    }

    @Test
    fun `applyUpdate rejects out of sync updates`() {
        val tracker = OrderBookTracker()
        tracker.loadSnapshot(
            OrderBook(
                lastUpdateId = 10,
                bids = listOf(OrderBookEntry(price = 100.0, quantity = 1.0)),
                asks = listOf(OrderBookEntry(price = 101.0, quantity = 1.0))
            )
        )

        val update = WsDepthUpdateData(
            symbol = "BTCUSDT",
            firstUpdateId = 15,
            finalUpdateId = 15,
            bids = listOf(listOf("100.0", "2.0")),
            asks = listOf(listOf("101.0", "2.0"))
        )

        val result = tracker.applyUpdate(update)

        assertFalse(result.ok)
        assertEquals(10L, tracker.lastUpdateId)
    }

    @Test
    fun `applyUpdate skips stale updates`() {
        val tracker = OrderBookTracker()
        tracker.loadSnapshot(
            OrderBook(
                lastUpdateId = 10,
                bids = listOf(OrderBookEntry(price = 100.0, quantity = 1.0)),
                asks = listOf(OrderBookEntry(price = 101.0, quantity = 1.0))
            )
        )

        val update = WsDepthUpdateData(
            symbol = "BTCUSDT",
            firstUpdateId = 5,
            finalUpdateId = 9,
            bids = listOf(listOf("100.0", "2.0")),
            asks = listOf(listOf("101.0", "2.0"))
        )

        val result = tracker.applyUpdate(update)

        assertTrue(result.ok)
        assertEquals(10L, tracker.lastUpdateId)
    }

    @Test
    fun `depthImbalance handles empty and levels`() {
        val tracker = OrderBookTracker()
        tracker.loadSnapshot(
            OrderBook(
                lastUpdateId = 1,
                bids = emptyList(),
                asks = emptyList()
            )
        )

        assertNull(tracker.depthImbalance(0))
        assertNull(tracker.depthImbalance(1))

        val update = WsDepthUpdateData(
            symbol = "BTCUSDT",
            firstUpdateId = 2,
            finalUpdateId = 2,
            bids = listOf(listOf("100.0", "2.0")),
            asks = listOf(listOf("101.0", "1.0"))
        )
        tracker.applyUpdate(update)

        val imbalance = tracker.depthImbalance(1)
        assertNotNull(imbalance)
    }
}
