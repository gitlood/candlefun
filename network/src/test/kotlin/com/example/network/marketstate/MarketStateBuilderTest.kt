package com.example.network.marketstate

import com.example.marketdata.model.MarketStateConfig
import com.example.network.dto.WsAggTradeData
import com.example.network.dto.WsBookTickerData
import com.example.platform.model.MarketState
import com.example.platform.model.OrderBook
import com.example.platform.model.OrderBookEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MarketStateBuilderTest {

    @Test
    fun `build uses book ticker and state windows`() {
        val config = MarketStateConfig(depthLevels = 2)
        val state = SymbolState(config)
        state.orderBook.loadSnapshot(
            OrderBook(
                lastUpdateId = 20,
                bids = listOf(OrderBookEntry(99.0, 1.0), OrderBookEntry(98.0, 2.0)),
                asks = listOf(OrderBookEntry(102.0, 1.5), OrderBookEntry(103.0, 2.5))
            )
        )
        state.lastBookTicker = WsBookTickerData(
            symbol = "BTCUSDT",
            eventTime = 100,
            updateId = 1,
            bestBidPrice = "100.0",
            bestBidQty = "3.0",
            bestAskPrice = "101.0",
            bestAskQty = "4.0"
        )
        state.lastTrade = WsAggTradeData(
            symbol = "BTCUSDT",
            eventTime = 150,
            aggTradeId = 1,
            price = "100.5",
            quantity = "0.2",
            firstTradeId = 1,
            lastTradeId = 1,
            tradeTime = 150,
            isBuyerMaker = false
        )
        state.lastBookEventTime = 100
        state.lastDepthEventTime = 200
        state.lastTradeEventTime = 150

        state.tradeWindow.add(1_000L, 0.2, isBuyerMaker = false)
        state.ofiWindow.add(1_000L, 1.5)
        state.volatility.addPrice(0L, 100.0)
        state.volatility.addPrice(500L, 101.0)
        state.volatility.addPrice(900L, 99.0)

        val builder = MarketStateBuilder(config)

        val result: MarketState = builder.build("BTCUSDT", state, nowMs = 1_000L)

        assertEquals("BTCUSDT", result.symbol)
        assertNotNull(result.bestBidPrice)
        assertNotNull(result.bestAskPrice)
        assertNotNull(result.spread)
        assertNotNull(result.midPrice)
        assertNotNull(result.microPrice)
        assertNotNull(result.eventTimeMs)
        assertEquals(100.0, result.bestBidPrice!!, 0.0001)
        assertEquals(101.0, result.bestAskPrice!!, 0.0001)
        assertEquals(1.0, result.spread!!, 0.0001)
        assertEquals(100.5, result.midPrice!!, 0.0001)
        assertEquals(200L, result.eventTimeMs!!)
        assertEquals(1.5, result.ofi1s, 0.0001)
        assertEquals(20L, result.bookUpdateId)
        assertEquals(2, result.bidLevels.size)
        assertEquals(2, result.askLevels.size)
    }
}
