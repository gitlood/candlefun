package com.example.network.marketstate

import com.example.marketdata.model.MarketStateConfig
import com.example.network.dto.WsAggTradeData
import com.example.network.dto.WsBookTickerData
import com.example.network.dto.WsDepthUpdateData
import com.example.platform.model.OrderBook
import com.example.platform.model.OrderBookEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketStateAssemblerTest {

    @Test
    fun `assembler updates state and builds snapshot`() = runBlocking {
        val config = MarketStateConfig(depthLevels = 1)
        val builder = MarketStateBuilder(config)
        val assembler = MarketStateAssembler(config, builder)

        assembler.ensureSymbols(listOf("BTCUSDT"))
        assembler.onSnapshot(
            symbol = "BTCUSDT",
            snapshot = OrderBook(
                lastUpdateId = 10,
                bids = listOf(OrderBookEntry(100.0, 1.0)),
                asks = listOf(OrderBookEntry(101.0, 1.0))
            )
        )

        assembler.onBookTicker(
            WsBookTickerData(
                symbol = "BTCUSDT",
                eventTime = 1_000L,
                updateId = 1L,
                bestBidPrice = "100.0",
                bestBidQty = "2.0",
                bestAskPrice = "101.0",
                bestAskQty = "2.0"
            ),
            nowMs = 1_000L
        )

        val needsResync = assembler.onDepthUpdate(
            WsDepthUpdateData(
                symbol = "BTCUSDT",
                eventTime = 1_200L,
                firstUpdateId = 11,
                finalUpdateId = 11,
                bids = listOf(listOf("102.0", "1.0")),
                asks = listOf(listOf("101.0", "1.0"))
            ),
            nowMs = 1_200L
        )

        assembler.onAggTrade(
            WsAggTradeData(
                symbol = "BTCUSDT",
                eventTime = 1_300L,
                aggTradeId = 1,
                price = "100.5",
                quantity = "0.2",
                firstTradeId = 1,
                lastTradeId = 1,
                tradeTime = 1_300L,
                isBuyerMaker = false
            ),
            nowMs = 1_300L
        )

        val state = assembler.build("BTCUSDT", nowMs = 1_400L)

        assertTrue(!needsResync)
        assertNotNull(state)
        assertEquals("BTCUSDT", state?.symbol)
        assertEquals(11L, state?.bookUpdateId)
    }

    @Test
    fun `onDepthUpdate returns true when out of sync`() = runBlocking {
        val config = MarketStateConfig(depthLevels = 1)
        val builder = MarketStateBuilder(config)
        val assembler = MarketStateAssembler(config, builder)

        assembler.ensureSymbols(listOf("BTCUSDT"))
        assembler.onSnapshot(
            symbol = "BTCUSDT",
            snapshot = OrderBook(
                lastUpdateId = 10,
                bids = listOf(OrderBookEntry(100.0, 1.0)),
                asks = listOf(OrderBookEntry(101.0, 1.0))
            )
        )

        val needsResync = assembler.onDepthUpdate(
            WsDepthUpdateData(
                symbol = "BTCUSDT",
                eventTime = 2_000L,
                firstUpdateId = 15,
                finalUpdateId = 15,
                bids = listOf(listOf("100.0", "2.0")),
                asks = listOf(listOf("101.0", "2.0"))
            ),
            nowMs = 2_000L
        )

        assertTrue(needsResync)
    }
}
