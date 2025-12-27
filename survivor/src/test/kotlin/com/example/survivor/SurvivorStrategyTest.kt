package com.example.survivor

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
import com.example.platform.model.enums.OrderSide
import com.example.platform.model.enums.OrderType
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class SurvivorStrategyTest {
    @Test
    fun `strategy skips entry in bad regime`() = runBlocking {
        val gateway = FakeExecutionGateway()
        val config = SurvivorConfig(
            symbol = "BTCUSDT",
            orderQty = 1.0,
            entryFundingThreshold = 0.0001,
            exitFundingThreshold = 0.0,
            basisStopAbsPct = 0.005,
            maxVolatility = 0.01,
            maxSpreadPct = 0.1,
            maxOiJumpPct = 1.0,
            oiWindowMs = 10_000L,
            maxHoldMs = 10_000L,
            orderTtlMs = 10_000L
        )
        val kpi = SurvivorKpiTracker(config)
        val strategy = SurvivorStrategy(gateway, config, kpi)

        strategy.onSnapshot(snapshot(funding = 0.002, mark = 101.0, index = 100.0, vol = 0.2))
        assertTrue(gateway.placed.isEmpty())
    }


    private fun snapshot(
        funding: Double,
        mark: Double,
        index: Double,
        vol: Double = 0.01,
        ts: Long = 1_000L
    ): SurvivorSnapshot {
        return SurvivorSnapshot(
            symbol = "BTCUSDT",
            timestampMs = ts,
            fundingRate = funding,
            nextFundingTimeMs = ts + 1,
            markPrice = mark,
            indexPrice = index,
            spreadPct = 0.0005,
            volatility = vol,
            openInterest = 1000.0
        )
    }
}

private class FakeExecutionGateway : ExecutionGateway {
    private var nextId = 1L
    val placed = mutableListOf<OrderRequest>()
    private val open = mutableListOf<ExecutionOrder>()
    var positions: List<Position> = emptyList()

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
