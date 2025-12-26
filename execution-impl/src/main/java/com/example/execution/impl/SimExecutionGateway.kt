package com.example.execution.impl

import com.example.account.domain.AccountStateRepository
import com.example.account.domain.BalanceSnapshot
import com.example.account.domain.Fill
import com.example.account.domain.Position
import com.example.account.domain.Price
import com.example.account.domain.Qty
import com.example.account.domain.Symbol
import com.example.account.domain.inventory.InventoryFill
import com.example.account.domain.inventory.InventoryStateRepository
import com.example.execution.domain.ExecutionGateway
import com.example.execution.domain.ExecutionOrder
import com.example.execution.domain.OrderCancelRequest
import com.example.execution.domain.OrderRequest
import com.example.execution.domain.OrderStatus
import com.example.platform.model.MarketState
import com.example.platform.model.enums.OrderSide
import com.example.platform.model.enums.OrderType
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicLong

class SimExecutionGateway(
    private val accountStateRepository: AccountStateRepository,
    private val inventoryStateRepository: InventoryStateRepository? = null,
    private val fillSimulator: ConservativeFillSimulator = ConservativeFillSimulator(),
    private val orderLatencyMs: Long = 0L,
    private val makerFeePct: Double = 0.0,
    private val takerFeePct: Double = 0.0,
    private val fillListener: ((FillRecord) -> Unit)? = null
) : ExecutionGateway {
    private val orderIdSeq = AtomicLong(1L)
    private val orders = mutableMapOf<Long, ExecutionOrder>()
    private val positions = mutableMapOf<Symbol, Position>()

    override suspend fun placeOrder(request: OrderRequest): ExecutionOrder {
        require(request.quantity.value > BigDecimal.ZERO) { "quantity must be > 0" }
        if (request.type == OrderType.LIMIT) {
            requireNotNull(request.price) { "limit order requires price" }
        }
        val orderId = orderIdSeq.getAndIncrement()
        val order = ExecutionOrder(
            symbol = request.symbol,
            orderId = orderId,
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
        orders[orderId] = order
        return order
    }

    override suspend fun cancelOrder(request: OrderCancelRequest): ExecutionOrder {
        val order = findOrder(request) ?: error("order not found")
        val canceled = order.copy(status = OrderStatus.CANCELED, transactTimeMs = System.currentTimeMillis())
        orders[order.orderId] = canceled
        return canceled
    }

    override suspend fun replaceOrder(cancelRequest: OrderCancelRequest, newRequest: OrderRequest): ExecutionOrder {
        cancelOrder(cancelRequest)
        return placeOrder(newRequest)
    }

    override suspend fun getOpenOrders(symbol: Symbol?): List<ExecutionOrder> {
        return orders.values.filter { order ->
            (order.status == OrderStatus.NEW || order.status == OrderStatus.PARTIALLY_FILLED) &&
                (symbol == null || order.symbol == symbol)
        }
    }

    override suspend fun getPositions(): List<Position> {
        return positions.values.toList()
    }

    suspend fun onMarketState(state: MarketState) {
        val now = state.eventTimeMs ?: state.timestampMs
        val openOrders = getOpenOrders(Symbol.of(state.symbol))
            .filter { order -> order.transactTimeMs + orderLatencyMs <= now }
        if (openOrders.isEmpty()) return

        val fills = fillSimulator.matchFills(state, openOrders)
        if (fills.isEmpty()) return

        for (fill in fills) {
            val order = orders[fill.orderId] ?: continue
            val newExecuted = (order.executedQty + fill.quantity)
                .let { if (it > order.originalQty) order.originalQty else it }
            val newStatus = if (newExecuted >= order.originalQty) {
                OrderStatus.FILLED
            } else {
                OrderStatus.PARTIALLY_FILLED
            }
            orders[order.orderId] = order.copy(
                executedQty = newExecuted,
                status = newStatus,
                transactTimeMs = fill.fillTimeMs
            )
            fillListener?.invoke(fill)
            applyFillToPosition(fill)
            recordFill(fill)
        }
    }

    private suspend fun applyFillToPosition(fill: FillRecord) {
        val signedQty = if (fill.side == OrderSide.BUY) fill.quantity else -fill.quantity
        val pos = positions[fill.symbol]
        val existingQty = pos?.quantity ?: Qty.ZERO
        val newQty = existingQty + signedQty
        val newAvg = if (newQty.isZero()) {
            Price.ZERO
        } else {
            val existingCost = (pos?.averagePrice ?: Price.ZERO).value.multiply(existingQty.value)
            val fillCost = fill.price.value.multiply(signedQty.value)
            Price(existingCost.add(fillCost).divide(newQty.value, java.math.MathContext.DECIMAL128))
        }
        positions[fill.symbol] = Position(symbol = fill.symbol, quantity = newQty, averagePrice = newAvg)

        inventoryStateRepository?.applyFill(
            InventoryFill(
                symbol = fill.symbol,
                signedQty = signedQty,
                price = fill.price,
                timestampMs = fill.fillTimeMs,
                fee = estimateFee(fill)
            )
        )
    }

    private fun recordFill(fill: FillRecord) {
        val repo = accountStateRepository
        if (repo is SimAccountStateRepository) {
            repo.recordFill(
                Fill(
                    symbol = fill.symbol,
                    price = fill.price,
                    quantity = fill.quantity,
                    fillTimeMs = fill.fillTimeMs,
                    isBuyerMaker = fill.side == OrderSide.BUY
                )
            )
        }
    }

    private fun findOrder(request: OrderCancelRequest): ExecutionOrder? {
        return when {
            request.orderId != null -> orders[request.orderId]
            request.clientOrderId != null -> orders.values.firstOrNull { it.clientOrderId == request.clientOrderId }
            else -> null
        }
    }

    private fun estimateFee(fill: FillRecord): com.example.account.domain.Money {
        val feeRate = makerFeePct
        if (feeRate <= 0.0) return com.example.account.domain.Money.ZERO
        val notional = fill.price.value.multiply(fill.quantity.value)
        return com.example.account.domain.Money(notional.multiply(java.math.BigDecimal.valueOf(feeRate)))
    }
}

class SimAccountStateRepository(
    initialBalances: List<BalanceSnapshot> = emptyList()
) : AccountStateRepository {
    private val balances = initialBalances.toMutableList()
    private val fills = mutableListOf<Fill>()

    override suspend fun getBalances(): List<BalanceSnapshot> {
        return balances.toList()
    }

    override suspend fun getFills(symbol: Symbol, sinceTimeMs: Long?): List<Fill> {
        return fills.filter { fill ->
            fill.symbol == symbol && (sinceTimeMs == null || fill.fillTimeMs >= sinceTimeMs)
        }
    }

    fun recordFill(fill: Fill) {
        fills.add(fill)
    }

    fun totalFills(): Int = fills.size

    fun setBalances(next: List<BalanceSnapshot>) {
        balances.clear()
        balances.addAll(next)
    }
}
