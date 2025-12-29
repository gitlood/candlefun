package com.example.ofi.kukanov

import com.example.account.domain.Position
import com.example.account.domain.Price
import com.example.account.domain.Qty
import com.example.account.domain.Symbol
import com.example.account.domain.BalanceSnapshot
import com.example.execution.domain.ExecutionGateway
import com.example.execution.domain.ExecutionOrder
import com.example.execution.domain.OrderCancelRequest
import com.example.execution.domain.OrderRequest
import com.example.execution.domain.OrderStatus
import com.example.execution.domain.TimeInForce
import com.example.platform.model.BookLevel
import com.example.platform.model.MarketState
import com.example.platform.model.enums.OrderSide
import com.example.platform.model.enums.OrderType
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class OfiKukanovStrategyTest {
    @Test
    fun `strategy enters and exits based on ofi`() = runBlocking {
        val gateway = FakeExecutionGateway()
        val config = OfiStrategyConfig(
            symbol = "BTCUSDT",
            entryThreshold = 0.0001,
            exitThreshold = 0.00005,
            minSignalIntervalMs = 0L,
            minSpreadSamples = 1,
            maxSpreadPct = 0.01,
            maxHoldMs = 10_000L
        )
        val strategy = OfiKukanovStrategy(gateway, config)

        strategy.onMarketState(state(1_000L, bidQty = 1.0))
        strategy.onMarketState(state(1_100L, bidQty = 3.0))
        assertEquals(1, gateway.placed.size)

        gateway.positions = listOf(
            Position(Symbol.of("BTCUSDT"), Qty.fromDouble(1.0), Price.fromDouble(100.0))
        )
        strategy.onMarketState(state(1_200L, bidQty = 1.0))
        assertEquals(2, gateway.placed.size)
    }

    private fun state(ts: Long, bidQty: Double): MarketState {
        val bid = BookLevel(price = 100.0, quantity = bidQty)
        val ask = BookLevel(price = 101.0, quantity = 1.0)
        return MarketState(
            symbol = "BTCUSDT",
            timestampMs = ts,
            eventTimeMs = ts,
            bestBidPrice = 100.0,
            bestBidQty = bidQty,
            bestAskPrice = 101.0,
            bestAskQty = 1.0,
            midPrice = 100.5,
            spread = 1.0,
            microPrice = 100.5,
            depthImbalance = 0.0,
            ofi1s = 0.0,
            tradeCount1s = 10,
            tradeVolume1s = 1.0,
            tradeImbalance1s = 0.0,
            lastTradePrice = 100.5,
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

private class FakeExecutionGateway : ExecutionGateway {
    private var nextId = 1L
    val placed = mutableListOf<OrderRequest>()
    var positions: List<Position> = emptyList()
    private val open = mutableListOf<ExecutionOrder>()

    override suspend fun placeOrder(request: OrderRequest): ExecutionOrder {
        placed.add(request)
        val order = ExecutionOrder(
            symbol = request.symbol,
            orderId = nextId++,
            clientOrderId = request.clientOrderId,
            price = request.price ?: Price.fromDouble(0.0),
            originalQty = request.quantity,
            executedQty = Qty.ZERO,
            status = OrderStatus.NEW,
            type = request.type,
            side = request.side,
            timeInForce = request.timeInForce ?: TimeInForce.GTC,
            transactTimeMs = System.currentTimeMillis()
        )
        open.add(order)
        return order
    }

    override suspend fun cancelOrder(request: OrderCancelRequest): ExecutionOrder {
        val order = open.firstOrNull { it.orderId == request.orderId }
            ?: ExecutionOrder(
                symbol = request.symbol,
                orderId = request.orderId ?: -1L,
                clientOrderId = request.clientOrderId,
                price = Price.fromDouble(0.0),
                originalQty = Qty.ZERO,
                executedQty = Qty.ZERO,
                status = OrderStatus.CANCELED,
                type = OrderType.LIMIT,
                side = OrderSide.BUY,
                timeInForce = TimeInForce.GTC,
                transactTimeMs = System.currentTimeMillis()
            )
        open.removeIf { it.orderId == order.orderId }
        return order.copy(status = OrderStatus.CANCELED)
    }

    override suspend fun replaceOrder(
        cancelRequest: OrderCancelRequest,
        newRequest: OrderRequest
    ): ExecutionOrder {
        cancelOrder(cancelRequest)
        return placeOrder(newRequest)
    }

    override suspend fun getOpenOrders(symbol: Symbol?): List<ExecutionOrder> {
        return if (symbol == null) open.toList() else open.filter { it.symbol == symbol }
    }

    override suspend fun getPositions(): List<Position> = positions
    override suspend fun getBalances(): List<BalanceSnapshot> = emptyList()
}
