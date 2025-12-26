package com.example.execution.impl

import com.example.account.domain.Price
import com.example.account.domain.Qty
import com.example.account.domain.Symbol
import com.example.execution.domain.ExecutionOrder
import com.example.execution.domain.OrderStatus
import com.example.execution.domain.TimeInForce
import com.example.platform.model.BookLevel
import com.example.platform.model.MarketState
import com.example.platform.model.enums.OrderSide
import com.example.platform.model.enums.OrderType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConservativeFillSimulatorTest {
    @Test
    fun `matches fills against eligible orders`() {
        val simulator = ConservativeFillSimulator(queueBufferMultiplier = 0.0, maxDepthLevels = 0)
        val orders = listOf(
            order(id = 1L, side = OrderSide.BUY, price = 101.0, qty = 0.4),
            order(id = 2L, side = OrderSide.BUY, price = 100.0, qty = 0.7),
            order(id = 3L, side = OrderSide.SELL, price = 99.0, qty = 0.5)
        )
        val state = marketState(
            lastTradePrice = 100.0,
            lastTradeQty = 1.0,
            lastTradeIsBuyerMaker = true,
            bidLevels = emptyList(),
            askLevels = emptyList()
        )

        val fills = simulator.matchFills(state, orders)

        assertEquals(2, fills.size)
        assertEquals(1L, fills[0].orderId)
        assertEquals(Qty.fromDouble(0.4), fills[0].quantity)
        assertEquals(2L, fills[1].orderId)
        assertEquals(Qty.fromDouble(0.6), fills[1].quantity)
    }

    @Test
    fun `returns empty when trade data missing`() {
        val simulator = ConservativeFillSimulator()
        val state = marketState(
            lastTradePrice = null,
            lastTradeQty = null,
            lastTradeIsBuyerMaker = null,
            bidLevels = emptyList(),
            askLevels = emptyList()
        )

        val fills = simulator.matchFills(state, emptyList())

        assertTrue(fills.isEmpty())
    }

    private fun order(id: Long, side: OrderSide, price: Double, qty: Double): ExecutionOrder {
        return ExecutionOrder(
            symbol = Symbol.of("BTCUSDT"),
            orderId = id,
            clientOrderId = null,
            price = Price.fromDouble(price),
            originalQty = Qty.fromDouble(qty),
            executedQty = Qty.ZERO,
            status = OrderStatus.NEW,
            type = OrderType.LIMIT,
            side = side,
            timeInForce = TimeInForce.GTC,
            transactTimeMs = 1L
        )
    }

    private fun marketState(
        lastTradePrice: Double?,
        lastTradeQty: Double?,
        lastTradeIsBuyerMaker: Boolean?,
        bidLevels: List<BookLevel>,
        askLevels: List<BookLevel>
    ): MarketState {
        return MarketState(
            symbol = "BTCUSDT",
            timestampMs = 1L,
            eventTimeMs = 1L,
            bestBidPrice = null,
            bestBidQty = null,
            bestAskPrice = null,
            bestAskQty = null,
            midPrice = null,
            spread = null,
            microPrice = null,
            depthImbalance = null,
            ofi1s = 0.0,
            tradeCount1s = 0,
            tradeVolume1s = 0.0,
            tradeImbalance1s = 0.0,
            lastTradePrice = lastTradePrice,
            lastTradeQty = lastTradeQty,
            lastTradeIsBuyerMaker = lastTradeIsBuyerMaker,
            vol1s = null,
            vol5s = null,
            vol10s = null,
            vol1m = null,
            vol5m = null,
            bookUpdateId = 0L,
            bidLevels = bidLevels,
            askLevels = askLevels
        )
    }
}
