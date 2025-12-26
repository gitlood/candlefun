package com.example.execution.impl

import com.example.account.domain.Price
import com.example.account.domain.Qty
import com.example.account.domain.Symbol
import com.example.execution.domain.ExecutionOrder
import com.example.platform.model.BookLevel
import com.example.platform.model.MarketState
import com.example.platform.model.enums.OrderSide
import com.example.platform.model.enums.OrderType
import java.math.BigDecimal

data class FillRecord(
    val orderId: Long,
    val symbol: Symbol,
    val side: OrderSide,
    val price: Price,
    val quantity: Qty,
    val fillTimeMs: Long
)

class ConservativeFillSimulator(
    private val queueBufferMultiplier: Double = 1.0,
    private val maxDepthLevels: Int = 5
) {
    fun matchFills(state: MarketState, openOrders: List<ExecutionOrder>): List<FillRecord> {
        val tradePrice = state.lastTradePrice ?: return emptyList()
        val tradeQty = state.lastTradeQty ?: return emptyList()
        if (tradeQty <= 0.0) return emptyList()
        val isBuyerMaker = state.lastTradeIsBuyerMaker ?: return emptyList()

        val eligibleSide = if (isBuyerMaker) OrderSide.BUY else OrderSide.SELL
        val candidates = openOrders
            .filter { it.side == eligibleSide && it.type == OrderType.LIMIT }
            .sortedBy { it.orderId }

        var remaining = BigDecimal.valueOf(tradeQty)
        val fills = mutableListOf<FillRecord>()
        for (order in candidates) {
            if (remaining <= BigDecimal.ZERO) break
            if (!priceMatches(order, Price.fromDouble(tradePrice), eligibleSide)) continue
            val openQty = order.originalQty - order.executedQty
            if (openQty <= Qty.ZERO) continue
            val queueAhead = estimateQueueAhead(state, order, eligibleSide)
            val available = if (queueAhead == null) {
                remaining
            } else {
                val availableQty = BigDecimal.valueOf(tradeQty - queueAhead)
                if (availableQty <= BigDecimal.ZERO) continue
                if (availableQty < remaining) availableQty else remaining
            }
            val fillQty = if (openQty.value <= available) openQty.value else available
            remaining = remaining.subtract(fillQty)
            fills.add(
                FillRecord(
                    orderId = order.orderId,
                    symbol = order.symbol,
                    side = order.side,
                    price = order.price,
                    quantity = Qty(fillQty),
                    fillTimeMs = state.eventTimeMs ?: state.timestampMs
                )
            )
        }
        return fills
    }

    private fun priceMatches(order: ExecutionOrder, tradePrice: Price, side: OrderSide): Boolean {
        return when (side) {
            OrderSide.BUY -> order.price >= tradePrice
            OrderSide.SELL -> order.price <= tradePrice
        }
    }

    private fun estimateQueueAhead(state: MarketState, order: ExecutionOrder, side: OrderSide): Double? {
        val levels = when (side) {
            OrderSide.BUY -> state.bidLevels
            OrderSide.SELL -> state.askLevels
        }.take(maxDepthLevels)
        if (levels.isEmpty()) return null
        val price = order.price.value.toDouble()
        val queueAhead = if (side == OrderSide.BUY) {
            sumLevels(levels) { level -> level.price > price }
        } else {
            sumLevels(levels) { level -> level.price < price }
        }
        val atLevel = levels.firstOrNull { it.price == price }?.quantity ?: 0.0
        val buffer = if (queueBufferMultiplier > 0.0) atLevel * queueBufferMultiplier else 0.0
        return queueAhead + atLevel + buffer
    }

    private fun sumLevels(levels: List<BookLevel>, predicate: (BookLevel) -> Boolean): Double {
        var sum = 0.0
        for (level in levels) {
            if (predicate(level)) {
                sum += level.quantity
            }
        }
        return sum
    }
}
