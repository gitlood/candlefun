package com.example.avellaneda

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
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AvellanedaMmStrategyTest {
    @Test
    fun `places bid and ask orders when market state is healthy`() = runBlocking {
        val gateway = FakeGateway()
        val config = AvellanedaMmConfig.default("BTCUSDT").copy(
            orderQty = 0.01,
            minSpreadPct = 0.0001,
            priceTick = 0.01,
            qtyStep = 0.0001,
            quoteRefreshMs = 0,
            gateCooldownMs = 0,
            maxInventory = 1.0
        )
        val strategy = AvellanedaMmStrategy(gateway, config)

        strategy.onMarketState(marketState(symbol = "BTCUSDT", mid = 100.0, spread = 0.01))

        assertEquals(2, gateway.openOrders.size)
        val sides = gateway.openOrders.map { it.side }.toSet()
        assertTrue(sides.contains(OrderSide.BUY))
        assertTrue(sides.contains(OrderSide.SELL))
    }

    @Test
    fun `gates and cancels when spread too wide`() = runBlocking {
        val gateway = FakeGateway()
        val config = AvellanedaMmConfig.default("BTCUSDT").copy(
            orderQty = 0.01,
            maxSpreadPct = 0.0001,
            priceTick = 0.01,
            qtyStep = 0.0001,
            quoteRefreshMs = 0,
            gateCooldownMs = 0,
            maxInventory = 1.0
        )
        val strategy = AvellanedaMmStrategy(gateway, config)

        strategy.onMarketState(marketState(symbol = "BTCUSDT", mid = 100.0, spread = 0.01))
        assertEquals(2, gateway.openOrders.size)

        strategy.onMarketState(marketState(symbol = "BTCUSDT", mid = 100.0, spread = 5.0))

        assertTrue(gateway.canceledOrderIds.isNotEmpty())
    }

    private fun marketState(symbol: String, mid: Double, spread: Double): MarketState {
        return MarketState(
            symbol = symbol,
            timestampMs = 100L,
            eventTimeMs = 100L,
            bestBidPrice = mid - spread / 2.0,
            bestBidQty = 1.0,
            bestAskPrice = mid + spread / 2.0,
            bestAskQty = 1.0,
            midPrice = mid,
            spread = spread,
            microPrice = mid,
            depthImbalance = 0.0,
            ofi1s = 0.0,
            tradeCount1s = 0,
            tradeVolume1s = 0.0,
            tradeImbalance1s = 0.0,
            lastTradePrice = mid,
            lastTradeQty = 1.0,
            lastTradeIsBuyerMaker = true,
            vol1s = 0.0,
            vol5s = 0.0,
            vol10s = 0.0,
            vol1m = 0.0,
            vol5m = 0.0,
            bookUpdateId = 1L,
            bidLevels = listOf(BookLevel(mid - spread / 2.0, 1.0)),
            askLevels = listOf(BookLevel(mid + spread / 2.0, 1.0))
        )
    }

    private class FakeGateway : ExecutionGateway {
        private val idCounter = AtomicLong(1L)
        val openOrders = mutableListOf<ExecutionOrder>()
        val canceledOrderIds = mutableListOf<Long>()

        override suspend fun placeOrder(request: OrderRequest): ExecutionOrder {
            val order = ExecutionOrder(
                symbol = request.symbol,
                orderId = idCounter.getAndIncrement(),
                clientOrderId = request.clientOrderId,
                price = request.price ?: Price.ZERO,
                originalQty = request.quantity,
                executedQty = Qty.ZERO,
                status = OrderStatus.NEW,
                type = request.type,
                side = request.side,
                timeInForce = request.timeInForce,
                transactTimeMs = System.currentTimeMillis()
            )
            openOrders.add(order)
            return order
        }

        override suspend fun cancelOrder(request: OrderCancelRequest): ExecutionOrder {
            val order = openOrders.first { it.orderId == request.orderId }
            openOrders.remove(order)
            canceledOrderIds.add(order.orderId)
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
            return if (symbol == null) openOrders.toList()
            else openOrders.filter { it.symbol == symbol }
        }

        override suspend fun getPositions(): List<Position> = emptyList()
        override suspend fun getBalances(): List<BalanceSnapshot> = emptyList()
    }
}
