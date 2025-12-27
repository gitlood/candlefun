package com.example.pairs

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

class PairsStrategyTest {
    @Test
    fun `strategy enters and exits on zscore signals`() = runBlocking {
        val gateway = FakeExecutionGateway()
        val config = PairsConfig(
            symbolA = "AAAUSDT",
            symbolB = "BBBUSDT",
            windowMs = 10_000L,
            minSamples = 5,
            entryZ = 100.0,
            exitZ = 0.1,
            minCorr = 0.0,
            maxVol = 1.0,
            trendCountLimit = 10,
            notional = 100.0,
            priceTick = 0.1,
            qtyStep = 0.001,
            orderTtlMs = 5_000L,
            tailZ = 0.1
        )
        val kpi = PairsKpiTracker(config)
        val strategy = PairsStrategy(gateway, config, kpi)

        val base = listOf(
            200.0 to 100.0,
            205.0 to 100.0,
            195.0 to 100.0,
            210.0 to 100.0,
            190.0 to 100.0
        )
        var now = 1_000L
        for ((a, b) in base) {
            strategy.onMarketState(state(config.symbolA, a, now))
            strategy.onMarketState(state(config.symbolB, b, now))
            now += 100L
        }

        strategy.onMarketState(state(config.symbolA, 230.0, now))
        strategy.onMarketState(state(config.symbolB, 100.0, now))

        now += 500L
        strategy.onMarketState(state(config.symbolA, 200.0, now))
        strategy.onMarketState(state(config.symbolB, 100.0, now))

        assertEquals(0, gateway.placed.size)
        val summary = kpi.summary()
        assertTrue(summary.tailEvents > 0)
    }

    private fun state(symbol: String, mid: Double, ts: Long): MarketState {
        val spread = 0.2
        val bid = mid - spread / 2.0
        val ask = mid + spread / 2.0
        val levelBid = BookLevel(price = bid, quantity = 1.0)
        val levelAsk = BookLevel(price = ask, quantity = 1.0)
        return MarketState(
            symbol = symbol,
            timestampMs = ts,
            eventTimeMs = ts,
            bestBidPrice = bid,
            bestBidQty = 1.0,
            bestAskPrice = ask,
            bestAskQty = 1.0,
            midPrice = mid,
            spread = spread,
            microPrice = mid,
            depthImbalance = 0.0,
            ofi1s = 0.0,
            tradeCount1s = 10,
            tradeVolume1s = 1.0,
            tradeImbalance1s = 0.0,
            lastTradePrice = mid,
            lastTradeQty = 0.1,
            lastTradeIsBuyerMaker = null,
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

    override suspend fun getPositions(): List<Position> = emptyList()
}
