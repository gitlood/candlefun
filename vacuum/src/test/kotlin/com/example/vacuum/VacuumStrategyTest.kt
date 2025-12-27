package com.example.vacuum

import com.example.account.domain.Position
import com.example.account.domain.Price
import com.example.account.domain.Qty
import com.example.account.domain.Symbol
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
import kotlin.test.assertTrue

class VacuumStrategyTest {
    @Test
    fun `strategy enters and exits on vacuum signals`() = runBlocking {
        val gateway = FakeExecutionGateway()
        val config = VacuumConfig(
            symbol = "BTCUSDT",
            depthWindowMs = 5_000L,
            spreadWindowMs = 5_000L,
            depthDropPct = 0.2,
            depthRefillPct = 0.05,
            spreadWidenPct = 0.1,
            maxSpreadPct = 0.02,
            minTradeCount1s = 1,
            minTradeImbalance1s = 0.5,
            orderQty = 1.0,
            priceTick = 0.1,
            qtyStep = 0.1,
            entryCooldownMs = 0L,
            orderTtlMs = 10_000L,
            maxHoldMs = 10_000L,
            trailingStopBps = 50.0,
            slippagePauseBps = 100.0,
            tailLossBps = 200.0,
            maxTailLosses = 10,
            pauseMs = 0L
        )
        val kpi = VacuumKpiTracker(config)
        val strategy = VacuumStrategy(gateway, config, kpi)

        var now = 1_000L
        strategy.onMarketState(state("BTCUSDT", now, mid = 100.0, depthQty = 10.0, spread = 0.05))
        now += 100L
        strategy.onMarketState(state("BTCUSDT", now, mid = 100.0, depthQty = 4.0, spread = 0.08))

        assertEquals(1, gateway.placed.size)

        gateway.positions = listOf(
            Position(Symbol.of("BTCUSDT"), Qty.fromDouble(1.0), Price.fromDouble(100.0))
        )
        now += 1_000L
        strategy.onMarketState(state("BTCUSDT", now, mid = 100.0, depthQty = 9.5, spread = 0.05))

        assertEquals(2, gateway.placed.size)
        assertTrue(kpi.summary().cancelRate == null || kpi.summary().cancelRate!! >= 0.0)
    }

    private fun state(symbol: String, ts: Long, mid: Double, depthQty: Double, spread: Double): MarketState {
        val bid = mid - spread / 2.0
        val ask = mid + spread / 2.0
        val levelBid = BookLevel(price = bid, quantity = depthQty)
        val levelAsk = BookLevel(price = ask, quantity = depthQty)
        return MarketState(
            symbol = symbol,
            timestampMs = ts,
            eventTimeMs = ts,
            bestBidPrice = bid,
            bestBidQty = depthQty,
            bestAskPrice = ask,
            bestAskQty = depthQty,
            midPrice = mid,
            spread = spread,
            microPrice = mid,
            depthImbalance = 0.0,
            ofi1s = 0.0,
            tradeCount1s = 5,
            tradeVolume1s = 1.0,
            tradeImbalance1s = 1.0,
            lastTradePrice = mid,
            lastTradeQty = 0.1,
            lastTradeIsBuyerMaker = true,
            vol1s = 0.01,
            vol5s = 0.01,
            vol10s = 0.01,
            vol1m = 0.01,
            vol5m = 0.01,
            bookUpdateId = ts,
            bidLevels = listOf(levelBid),
            askLevels = listOf(levelAsk)
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
}
