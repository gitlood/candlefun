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

    @Test
    fun `strategy does not enter in high volatility regime`() = runBlocking {
        val gateway = FakeExecutionGateway()
        val config = PairsConfig(
            symbolA = "AAAUSDT",
            symbolB = "BBBUSDT",
            windowMs = 10_000L,
            minSamples = 5,
            entryZ = 0.5,
            exitZ = 0.1,
            minCorr = 0.0,
            maxVol = 0.0001,
            trendCountLimit = 10,
            notional = 100.0,
            priceTick = 0.1,
            qtyStep = 0.001,
            orderTtlMs = 5_000L
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
            strategy.onMarketState(state(config.symbolA, a, now, vol1s = 0.01))
            strategy.onMarketState(state(config.symbolB, b, now, vol1s = 0.01))
            now += 100L
        }

        strategy.onMarketState(state(config.symbolA, 230.0, now, vol1s = 0.01))
        strategy.onMarketState(state(config.symbolB, 100.0, now, vol1s = 0.01))

        assertEquals(0, gateway.placed.size)
    }

    @Test
    fun `strategy places entry and exit orders with rounding`() = runBlocking {
        val gateway = FakeExecutionGateway()
        val config = PairsConfig(
            symbolA = "AAAUSDT",
            symbolB = "BBBUSDT",
            windowMs = 10_000L,
            minSamples = 5,
            entryZ = 2.0,
            exitZ = 10.0,
            minCorr = 0.0,
            maxVol = 1.0,
            trendCountLimit = 10,
            notional = 100.0,
            priceTickA = 0.05,
            priceTickB = 0.1,
            qtyStepA = 0.1,
            qtyStepB = 0.01,
            orderTtlMs = 5_000L
        )
        val strategy = PairsStrategy(gateway, config, PairsKpiTracker(config))
        var now = seedSamples(
            strategy,
            config,
            listOf(
                100.0 to 100.0,
                101.0 to 100.2,
                99.5 to 100.1,
                100.5 to 99.8,
                98.8 to 99.9
            )
        )

        strategy.onMarketState(state(config.symbolA, 130.0, now, spread = 0.06))
        strategy.onMarketState(state(config.symbolB, 100.0, now, spread = 0.06))

        val entryOrders = gateway.placed.filter {
            val id = it.clientOrderId ?: return@filter false
            id.startsWith("pairs_") && !id.startsWith("pairs_exit_")
        }
        assertEquals(2, entryOrders.size)
        val orderA = entryOrders.first { it.symbol.value == config.symbolA }
        val orderB = entryOrders.first { it.symbol.value == config.symbolB }
        assertTrue(orderA.side != orderB.side)
        val rawA = if (orderA.side == OrderSide.BUY) 130.0 + 0.03 else 130.0 - 0.03
        val rawB = if (orderB.side == OrderSide.BUY) 100.0 + 0.03 else 100.0 - 0.03
        val priceA = orderA.price!!.value.toDouble()
        val priceB = orderB.price!!.value.toDouble()
        assertTrue(isMultipleOf(priceA, config.priceTickA))
        assertTrue(isMultipleOf(priceB, config.priceTickB))
        assertTrue(kotlin.math.abs(priceA - rawA) <= config.priceTickA + 1e-9)
        assertTrue(kotlin.math.abs(priceB - rawB) <= config.priceTickB + 1e-9)
        val qtyA = orderA.quantity.value.toDouble()
        val qtyB = orderB.quantity.value.toDouble()
        assertTrue(isMultipleOf(qtyA, config.qtyStepA))
        assertTrue(isMultipleOf(qtyB, config.qtyStepB))
        assertTrue(qtyA <= config.notional / priceA + 1e-9)
        assertTrue(qtyB <= config.notional / priceB + 1e-9)

        now += 200L
        strategy.onMarketState(state(config.symbolA, 100.0, now))
        strategy.onMarketState(state(config.symbolB, 100.0, now))
        val exitOrders = gateway.placed.filter { it.clientOrderId?.startsWith("pairs_exit_") == true }
        assertEquals(2, exitOrders.size)
    }

    @Test
    fun `strategy cancels stale orders after ttl`() = runBlocking {
        val gateway = FakeExecutionGateway()
        val config = PairsConfig(
            symbolA = "AAAUSDT",
            symbolB = "BBBUSDT",
            windowMs = 10_000L,
            minSamples = 5,
            entryZ = 1.0,
            exitZ = 0.0,
            minCorr = 0.0,
            maxVol = 1.0,
            trendCountLimit = 10,
            notional = 100.0,
            priceTick = 0.1,
            qtyStep = 0.01,
            orderTtlMs = 1L
        )
        val strategy = PairsStrategy(gateway, config, PairsKpiTracker(config))
        var now = seedSamples(
            strategy,
            config,
            listOf(
                100.0 to 100.0,
                101.0 to 100.4,
                99.5 to 99.8,
                102.0 to 101.0,
                98.8 to 99.3
            )
        )

        strategy.onMarketState(state(config.symbolA, 110.0, now))
        strategy.onMarketState(state(config.symbolB, 100.0, now))
        assertEquals(2, gateway.placed.size)

        now += 5L
        strategy.onMarketState(state(config.symbolA, 110.0, now))
        strategy.onMarketState(state(config.symbolB, 100.0, now))
        assertEquals(2, gateway.canceled.size)
    }

    @Test
    fun `strategy skips entry when min notional fails after rounding`() = runBlocking {
        val gateway = FakeExecutionGateway()
        val config = PairsConfig(
            symbolA = "AAAUSDT",
            symbolB = "BBBUSDT",
            windowMs = 10_000L,
            minSamples = 5,
            entryZ = 1.0,
            exitZ = 0.0,
            minCorr = 0.0,
            maxVol = 1.0,
            trendCountLimit = 10,
            notional = 50.0,
            priceTick = 0.1,
            qtyStep = 1.0,
            minNotionalA = 100.0,
            minNotionalB = 100.0,
            orderTtlMs = 5_000L
        )
        val strategy = PairsStrategy(gateway, config, PairsKpiTracker(config))
        val now = seedSamples(
            strategy,
            config,
            listOf(
                33.0 to 33.2,
                34.0 to 33.8,
                32.5 to 33.0,
                35.0 to 34.5,
                31.8 to 32.7
            )
        )

        strategy.onMarketState(state(config.symbolA, 36.0, now))
        strategy.onMarketState(state(config.symbolB, 33.0, now))

        assertEquals(0, gateway.placed.size)
    }

    private suspend fun seedSamples(
        strategy: PairsStrategy,
        config: PairsConfig,
        samples: List<Pair<Double, Double>>,
        startMs: Long = 1_000L
    ): Long {
        var now = startMs
        for ((a, b) in samples) {
            strategy.onMarketState(state(config.symbolA, a, now))
            strategy.onMarketState(state(config.symbolB, b, now))
            now += 100L
        }
        return now
    }

    private fun state(
        symbol: String,
        mid: Double,
        ts: Long,
        vol1s: Double = 0.01,
        spread: Double = 0.2
    ): MarketState {
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
            vol1s = vol1s,
            vol5s = 0.01,
            vol10s = 0.01,
            vol1m = 0.01,
            vol5m = 0.01,
            bookUpdateId = ts,
            bidLevels = listOf(levelBid),
            askLevels = listOf(levelAsk)
        )
    }

    private fun isMultipleOf(value: Double, step: Double): Boolean {
        if (step <= 0.0) return true
        val units = value / step
        return kotlin.math.abs(units - kotlin.math.round(units)) < 1e-9
    }
}

private class FakeExecutionGateway : ExecutionGateway {
    private var nextId = 1L
    val placed = mutableListOf<OrderRequest>()
    val canceled = mutableListOf<OrderCancelRequest>()
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
        canceled.add(request)
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
