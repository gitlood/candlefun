package com.example.avellaneda

import com.example.account.domain.Price
import com.example.account.domain.Qty
import com.example.account.domain.Symbol
import com.example.execution.domain.ExecutionGateway
import com.example.execution.domain.OrderCancelRequest
import com.example.execution.domain.OrderRequest
import com.example.execution.domain.TimeInForce
import com.example.platform.model.MarketState
import com.example.platform.model.enums.OrderSide
import com.example.platform.model.enums.OrderType
import kotlin.math.abs

class AvellanedaMmStrategy(
    private val gateway: ExecutionGateway,
    private val config: AvellanedaMmConfig
) {
    private var lastActionMs: Long = 0L
    private var lastBidOrderId: Long? = null
    private var lastAskOrderId: Long? = null

    suspend fun onMarketState(state: MarketState) {
        if (state.symbol != config.symbol) return
        val now = state.eventTimeMs ?: state.timestampMs
        if (now - lastActionMs < config.quoteRefreshMs) return

        val mid = state.midPrice ?: state.microPrice ?: return
        val spread = state.spread ?: return
        if (spread <= 0.0) return
        val spreadPct = spread / mid
        if (spreadPct < config.minSpreadPct) {
            cancelAll()
            lastActionMs = now
            return
        }

        val positionQty = gateway.getPositions()
            .firstOrNull { it.symbol.value == config.symbol }
            ?.quantity
            ?.toDouble()
            ?: 0.0
        val skew = positionQty * config.inventorySkew

        var bid = mid - spread / 2.0 - skew
        var ask = mid + spread / 2.0 - skew

        bid = roundDown(bid, config.priceTick)
        ask = roundUp(ask, config.priceTick)
        if (bid >= ask) return

        val bestBid = state.bestBidPrice
        val bestAsk = state.bestAskPrice
        val allowBid = positionQty < config.maxInventory && (bestAsk == null || bid < bestAsk)
        val allowAsk = positionQty > -config.maxInventory && (bestBid == null || ask > bestBid)

        if (allowBid) {
            lastBidOrderId = ensureOrder(
                side = OrderSide.BUY,
                price = bid,
                qty = config.orderQty,
                existingId = lastBidOrderId,
                now = now
            )
        } else {
            cancelIfExists(lastBidOrderId)
            lastBidOrderId = null
        }

        if (allowAsk) {
            lastAskOrderId = ensureOrder(
                side = OrderSide.SELL,
                price = ask,
                qty = config.orderQty,
                existingId = lastAskOrderId,
                now = now
            )
        } else {
            cancelIfExists(lastAskOrderId)
            lastAskOrderId = null
        }

        lastActionMs = now
    }

    private suspend fun ensureOrder(
        side: OrderSide,
        price: Double,
        qty: Double,
        existingId: Long?,
        now: Long
    ): Long? {
        val openOrders = gateway.getOpenOrders(Symbol.of(config.symbol)).filter { it.side == side }
        val existing = openOrders.firstOrNull { it.orderId == existingId } ?: openOrders.firstOrNull()
        val stale = existing != null && (now - existing.transactTimeMs) > config.maxQuoteAgeMs
        val priceChanged = existing != null && abs(existing.price.value.toDouble() - price) >= config.priceTick / 2.0

        if (existing != null && !stale && !priceChanged) return existing.orderId

        if (existing != null) {
            gateway.cancelOrder(OrderCancelRequest(Symbol.of(config.symbol), existing.orderId))
        }

        val roundedQty = roundDown(qty, config.qtyStep)
        val placed = gateway.placeOrder(
            OrderRequest(
                symbol = Symbol.of(config.symbol),
                side = side,
                type = OrderType.LIMIT,
                quantity = Qty.fromDouble(roundedQty),
                price = Price.fromDouble(price),
                timeInForce = TimeInForce.GTC
            )
        )
        return placed.orderId
    }

    private suspend fun cancelAll() {
        cancelIfExists(lastBidOrderId)
        cancelIfExists(lastAskOrderId)
        lastBidOrderId = null
        lastAskOrderId = null
    }

    private suspend fun cancelIfExists(orderId: Long?) {
        if (orderId == null) return
        runCatching { gateway.cancelOrder(OrderCancelRequest(Symbol.of(config.symbol), orderId)) }
    }

    private fun roundDown(value: Double, step: Double): Double {
        if (step <= 0.0) return value
        return kotlin.math.floor(value / step) * step
    }

    private fun roundUp(value: Double, step: Double): Double {
        if (step <= 0.0) return value
        return kotlin.math.ceil(value / step) * step
    }
}
