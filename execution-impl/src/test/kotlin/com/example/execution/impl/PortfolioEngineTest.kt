package com.example.execution.impl

import com.example.account.domain.Asset
import com.example.account.domain.BalanceSnapshot
import com.example.account.domain.Position
import com.example.account.domain.Price
import com.example.account.domain.Qty
import com.example.account.domain.Symbol
import com.example.execution.domain.ExecutionGateway
import com.example.execution.domain.IntentStrategy
import com.example.execution.domain.OrderCancelRequest
import com.example.execution.domain.OrderRequest
import com.example.execution.domain.OrderStatus
import com.example.execution.domain.RiskBudget
import com.example.execution.domain.StrategyContext
import com.example.execution.domain.StrategyIntent
import com.example.platform.model.MarketState
import com.example.platform.model.enums.OrderSide
import com.example.platform.model.enums.OrderType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class PortfolioEngineTest {
    @Test
    fun `latency filter blocks routing`() = runBlocking {
        val gateway = FakeExecutionGateway()
        val allocator = IntentAllocator(RiskBudget(total = 1_000.0))
        val policy = ExecutionPolicy(gateway)
        val strategy = FixedIntentStrategy("test_latency", 1.0)
        val engine = PortfolioEngine(
            gateway = gateway,
            allocator = allocator,
            policy = policy,
            strategies = listOf(strategy),
            maxLatencyMs = 10L
        )
        val state = marketState(
            symbol = "BTCUSDT",
            eventTimeMs = System.currentTimeMillis() - 1_000L
        )
        engine.onMarketState(state)
        assertEquals(0, gateway.placedOrders.size)
    }

    @Test
    fun `risk caps scale routing decisions`() = runBlocking {
        val gateway = FakeExecutionGateway()
        val allocator = IntentAllocator(RiskBudget(total = 1_000.0))
        val policy = ExecutionPolicy(gateway)
        val strategy = FixedIntentStrategy("cap_test", 1.0)
        val engine = PortfolioEngine(
            gateway = gateway,
            allocator = allocator,
            policy = policy,
            strategies = listOf(strategy),
            maxTotalNotionalUsd = 50.0
        )
        val state = marketState(symbol = "BTCUSDT", mid = 100.0, spread = 1.0)
        engine.onMarketState(state)
        val order = gateway.placedOrders.single()
        assertEquals(0.5, order.quantity.toDouble())
    }

    @Test
    fun `kill switch cancels open orders on drawdown`() = runBlocking {
        val gateway = FakeExecutionGateway()
        val allocator = IntentAllocator(RiskBudget(total = 1_000.0))
        val policy = ExecutionPolicy(gateway)
        val strategy = FixedIntentStrategy("drawdown_test", 1.0)
        val engine = PortfolioEngine(
            gateway = gateway,
            allocator = allocator,
            policy = policy,
            strategies = listOf(strategy),
            balanceRefreshMs = 0L,
            maxDrawdownPct = 0.1
        )
        gateway.balances = listOf(balance("USDT", 100.0))
        val state = marketState(symbol = "BTCUSDT", mid = 100.0, spread = 1.0)
        engine.onMarketState(state)
        gateway.balances = listOf(balance("USDT", 80.0))
        engine.onMarketState(state.copy(timestampMs = state.timestampMs + 1_000L))
        assertTrue(gateway.cancelRequests.isNotEmpty())
    }

    @Test
    fun `regime gating blocks disabled strategies`() = runBlocking {
        val gateway = FakeExecutionGateway()
        val allocator = IntentAllocator(RiskBudget(total = 1_000.0))
        val policy = ExecutionPolicy(gateway)
        val strategy = FixedIntentStrategy("avellaneda_mm", 1.0)
        val regimeEngine = RegimeEngine(
            minSpreadBps = 1.0,
            maxSpreadBps = 30.0,
            maxVol1s = 0.02,
            maxTradeImbalance1s = 1.0,
            minTradeCount1s = 1
        )
        val engine = PortfolioEngine(
            gateway = gateway,
            allocator = allocator,
            policy = policy,
            strategies = listOf(strategy),
            regimeEngine = regimeEngine
        )
        val state = marketState(
            symbol = "BTCUSDT",
            mid = 100.0,
            spread = 1.0,
            tradeCount1s = 1,
            tradeImbalance1s = 10.0
        )
        engine.onMarketState(state)
        assertEquals(0, gateway.placedOrders.size)
    }

    private fun marketState(
        symbol: String,
        mid: Double = 100.0,
        spread: Double = 1.0,
        eventTimeMs: Long = System.currentTimeMillis(),
        tradeCount1s: Int = 0,
        tradeImbalance1s: Double = 0.0
    ): MarketState {
        return MarketState(
            symbol = symbol,
            timestampMs = eventTimeMs,
            eventTimeMs = eventTimeMs,
            bestBidPrice = mid - spread / 2.0,
            bestBidQty = 1.0,
            bestAskPrice = mid + spread / 2.0,
            bestAskQty = 1.0,
            midPrice = mid,
            spread = spread,
            microPrice = null,
            depthImbalance = null,
            ofi1s = 0.0,
            tradeCount1s = tradeCount1s,
            tradeVolume1s = 0.0,
            tradeImbalance1s = tradeImbalance1s,
            lastTradePrice = null,
            lastTradeQty = null,
            lastTradeIsBuyerMaker = null,
            vol1s = 0.01,
            vol5s = null,
            vol10s = null,
            vol1m = null,
            vol5m = null,
            bookUpdateId = 0L,
            bidLevels = emptyList(),
            askLevels = emptyList()
        )
    }

    private fun balance(asset: String, free: Double): BalanceSnapshot {
        return BalanceSnapshot(
            asset = Asset.of(asset),
            free = Qty.fromDouble(free),
            locked = Qty.ZERO
        )
    }

    private class FixedIntentStrategy(
        override val id: String,
        private val delta: Double
    ) : IntentStrategy {
        override fun onMarketState(state: MarketState, context: StrategyContext): List<StrategyIntent> {
            return listOf(
                StrategyIntent(
                    strategyId = id,
                    symbol = Symbol.of(state.symbol),
                    desiredDelta = Qty.fromDouble(delta)
                )
            )
        }
    }

    private class FakeExecutionGateway : ExecutionGateway {
        val placedOrders = mutableListOf<OrderRequest>()
        val cancelRequests = mutableListOf<OrderCancelRequest>()
        private val openOrders = mutableListOf<com.example.execution.domain.ExecutionOrder>()
        var positions: List<Position> = emptyList()
        var balances: List<BalanceSnapshot> = emptyList()
        private var nextOrderId = 1L

        override suspend fun placeOrder(request: OrderRequest): com.example.execution.domain.ExecutionOrder {
            placedOrders.add(request)
            val order = com.example.execution.domain.ExecutionOrder(
                symbol = request.symbol,
                orderId = nextOrderId++,
                clientOrderId = request.clientOrderId,
                price = request.price ?: Price.fromDouble(0.0),
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

        override suspend fun cancelOrder(request: OrderCancelRequest): com.example.execution.domain.ExecutionOrder {
            cancelRequests.add(request)
            val order = openOrders.firstOrNull { it.orderId == request.orderId }
                ?: openOrders.firstOrNull()
                ?: com.example.execution.domain.ExecutionOrder(
                    symbol = request.symbol,
                    orderId = request.orderId ?: -1L,
                    clientOrderId = request.clientOrderId,
                    price = Price.fromDouble(0.0),
                    originalQty = Qty.ZERO,
                    executedQty = Qty.ZERO,
                    status = OrderStatus.CANCELED,
                    type = OrderType.LIMIT,
                    side = OrderSide.BUY,
                    timeInForce = null,
                    transactTimeMs = System.currentTimeMillis()
                )
            openOrders.removeIf { it.orderId == order.orderId }
            return order
        }

        override suspend fun replaceOrder(
            cancelRequest: OrderCancelRequest,
            newRequest: OrderRequest
        ): com.example.execution.domain.ExecutionOrder {
            cancelOrder(cancelRequest)
            return placeOrder(newRequest)
        }

        override suspend fun getOpenOrders(symbol: Symbol?): List<com.example.execution.domain.ExecutionOrder> {
            return if (symbol == null) openOrders.toList() else openOrders.filter { it.symbol == symbol }
        }

        override suspend fun getPositions(): List<Position> = positions

        override suspend fun getBalances(): List<BalanceSnapshot> = balances
    }
}
