package com.example.avellaneda.metrics

import com.example.platform.model.MarketState
import com.example.platform.model.enums.OrderSide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AdverseSelectionTrackerTest {
    @Test
    fun `records adverse selection after horizon`() {
        val tracker = AdverseSelectionTracker(longArrayOf(1_000L))
        tracker.recordFill("BTCUSDT", OrderSide.BUY, 100.0, 0L)

        tracker.onMarketState(state("BTCUSDT", mid = 99.0, ts = 1000L))

        val snapshot = tracker.snapshotBps("BTCUSDT")
        assertNotNull(snapshot[0])
        assertEquals(true, snapshot[0]!! > 0.0)
    }

    private fun state(symbol: String, mid: Double, ts: Long): MarketState {
        return MarketState(
            symbol = symbol,
            timestampMs = ts,
            eventTimeMs = ts,
            bestBidPrice = null,
            bestBidQty = null,
            bestAskPrice = null,
            bestAskQty = null,
            midPrice = mid,
            spread = null,
            microPrice = null,
            depthImbalance = null,
            ofi1s = 0.0,
            tradeCount1s = 0,
            tradeVolume1s = 0.0,
            tradeImbalance1s = 0.0,
            lastTradePrice = null,
            lastTradeQty = null,
            lastTradeIsBuyerMaker = null,
            vol1s = null,
            vol5s = null,
            vol10s = null,
            vol1m = null,
            vol5m = null,
            bookUpdateId = 0L,
            bidLevels = emptyList(),
            askLevels = emptyList()
        )
    }
}
