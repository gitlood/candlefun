package com.example.survivor

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
import com.example.platform.model.enums.OrderSide
import com.example.platform.model.enums.OrderType
import java.util.concurrent.atomic.AtomicLong

class SurvivorPaperGateway(
    private val fillListener: ((SurvivorFill) -> Unit)? = null
) : ExecutionGateway {
    private val orderIdSeq = AtomicLong(1L)
    private val positions = mutableMapOf<Symbol, Position>()

    override suspend fun placeOrder(request: OrderRequest): ExecutionOrder {
        val orderId = orderIdSeq.getAndIncrement()
        val order = ExecutionOrder(
            symbol = request.symbol,
            orderId = orderId,
            clientOrderId = request.clientOrderId,
            price = request.price ?: Price.ZERO,
            originalQty = request.quantity,
            executedQty = request.quantity,
            status = OrderStatus.FILLED,
            type = request.type,
            side = request.side,
            timeInForce = request.timeInForce ?: TimeInForce.GTC,
            transactTimeMs = System.currentTimeMillis()
        )
        applyFill(
            SurvivorFill(
                orderId = orderId,
                symbol = request.symbol,
                side = request.side,
                price = request.price ?: Price.ZERO,
                quantity = request.quantity,
                fillTimeMs = order.transactTimeMs
            )
        )
        return order
    }

    override suspend fun cancelOrder(request: OrderCancelRequest): ExecutionOrder {
        return ExecutionOrder(
            symbol = request.symbol,
            orderId = request.orderId ?: 0L,
            clientOrderId = request.clientOrderId,
            price = Price.ZERO,
            originalQty = Qty.ZERO,
            executedQty = Qty.ZERO,
            status = OrderStatus.CANCELED,
            type = OrderType.LIMIT,
            side = OrderSide.BUY,
            timeInForce = TimeInForce.GTC,
            transactTimeMs = System.currentTimeMillis()
        )
    }

    override suspend fun replaceOrder(
        cancelRequest: OrderCancelRequest,
        newRequest: OrderRequest
    ): ExecutionOrder {
        cancelOrder(cancelRequest)
        return placeOrder(newRequest)
    }

    override suspend fun getOpenOrders(symbol: Symbol?): List<ExecutionOrder> = emptyList()

    override suspend fun getPositions(): List<Position> = positions.values.toList()

    override suspend fun getBalances(): List<BalanceSnapshot> = emptyList()

    private fun applyFill(fill: SurvivorFill) {
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
        positions[fill.symbol] = Position(
            symbol = fill.symbol,
            quantity = newQty,
            averagePrice = newAvg
        )
        fillListener?.invoke(fill)
    }
}

data class SurvivorFill(
    val orderId: Long,
    val symbol: Symbol,
    val side: OrderSide,
    val price: Price,
    val quantity: Qty,
    val fillTimeMs: Long
)
