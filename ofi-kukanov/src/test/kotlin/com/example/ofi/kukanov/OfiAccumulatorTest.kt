package com.example.ofi.kukanov

import com.example.platform.model.BookLevel
import com.example.platform.model.MarketState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfiAccumulatorTest {
    @Test
    fun `accumulator computes normalized ofi`() {
        val accumulator = KukanovOfiAccumulator(KukanovOfiConfig(depthLevels = 1))
        val base = state(
            symbol = "BTCUSDT",
            ts = 1_000L,
            bidPrice = 100.0,
            bidQty = 1.0,
            askPrice = 101.0,
            askQty = 1.0
        )
        val next = state(
            symbol = "BTCUSDT",
            ts = 1_100L,
            bidPrice = 100.0,
            bidQty = 2.0,
            askPrice = 101.0,
            askQty = 1.0
        )
        accumulator.update(base)
        val signal = accumulator.update(next)
        assertTrue(signal.ofi > 0.0)
        assertTrue(signal.normalizedOfi != null && signal.normalizedOfi!! > 0.0)
        assertEquals(2.0 * 100.0 + 1.0 * 101.0, signal.depthNotional)
    }

    private fun state(
        symbol: String,
        ts: Long,
        bidPrice: Double,
        bidQty: Double,
        askPrice: Double,
        askQty: Double
    ): MarketState {
        val bid = BookLevel(price = bidPrice, quantity = bidQty)
        val ask = BookLevel(price = askPrice, quantity = askQty)
        val mid = (bidPrice + askPrice) / 2.0
        val spread = askPrice - bidPrice
        return MarketState(
            symbol = symbol,
            timestampMs = ts,
            eventTimeMs = ts,
            bestBidPrice = bidPrice,
            bestBidQty = bidQty,
            bestAskPrice = askPrice,
            bestAskQty = askQty,
            midPrice = mid,
            spread = spread,
            microPrice = mid,
            depthImbalance = 0.0,
            ofi1s = 0.0,
            tradeCount1s = 0,
            tradeVolume1s = 0.0,
            tradeImbalance1s = 0.0,
            lastTradePrice = mid,
            lastTradeQty = 0.1,
            lastTradeIsBuyerMaker = true,
            vol1s = 0.0,
            vol5s = 0.0,
            vol10s = 0.0,
            vol1m = 0.0,
            vol5m = 0.0,
            bookUpdateId = ts,
            bidLevels = listOf(bid),
            askLevels = listOf(ask)
        )
    }
}
