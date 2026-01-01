package com.example.execution.impl

import com.example.account.domain.Price
import com.example.account.domain.Qty
import com.example.account.domain.Symbol
import com.example.execution.domain.ExecutionGateway
import com.example.execution.domain.OrderCancelRequest
import com.example.execution.domain.OrderRequest
import com.example.execution.domain.RoutingDecision
import com.example.execution.domain.TimeInForce
import com.example.platform.model.MarketState
import com.example.platform.model.enums.OrderSide
import com.example.platform.model.enums.OrderType
import com.example.platform.report.Telemetry
import kotlin.math.abs

class ExecutionPolicy(
    private val gateway: ExecutionGateway
) {
    private val ttlOrders = mutableMapOf<Long, TtlOrder>()

    suspend fun onMarketState(state: MarketState, nowMs: Long) {
        cancelExpired(nowMs)
    }

    suspend fun route(decision: RoutingDecision, state: MarketState, nowMs: Long) {
        if (decision.netDelta.value.signum() == 0) return
        val side = if (decision.netDelta.value.signum() > 0) OrderSide.BUY else OrderSide.SELL
        val qty = Qty(decision.netDelta.value.abs())
        if (qty <= Qty.ZERO) return

        val price = resolvePrice(decision, state, side)
        if (price == null) {
            Telemetry.emit(
                type = "routing_skip",
                tsMs = nowMs,
                data = mapOf(
                    "symbol" to decision.symbol.value,
                    "reason" to "no_price",
                    "prefer_maker" to decision.preferMaker
                )
            )
            return
        }

        val request = OrderRequest(
            symbol = Symbol.of(state.symbol),
            side = side,
            type = OrderType.LIMIT,
            quantity = qty,
            price = Price.fromDouble(price),
            timeInForce = if (decision.preferMaker) TimeInForce.GTX else TimeInForce.IOC,
            clientOrderId = decision.clientOrderId
        )
        val order = gateway.placeOrder(request)
        val ttl = decision.ttlMs
        if (ttl != null && ttl > 0L) {
            ttlOrders[order.orderId] = TtlOrder(order.symbol, order.transactTimeMs + ttl)
        }
    }

    private suspend fun cancelExpired(nowMs: Long) {
        if (ttlOrders.isEmpty()) return
        val expired = ttlOrders.filterValues { it.expireAtMs <= nowMs }.keys.toList()
        if (expired.isEmpty()) return
        for (orderId in expired) {
            val order = ttlOrders[orderId] ?: continue
            runCatching { gateway.cancelOrder(OrderCancelRequest(symbol = order.symbol, orderId = orderId)) }
            ttlOrders.remove(orderId)
        }
    }

    private fun resolvePrice(decision: RoutingDecision, state: MarketState, side: OrderSide): Double? {
        val bestBid = state.bestBidPrice
        val bestAsk = state.bestAskPrice
        val mid = state.midPrice ?: state.microPrice
        val raw = if (decision.preferMaker) {
            if (side == OrderSide.BUY) bestBid else bestAsk
        } else {
            if (side == OrderSide.BUY) bestAsk else bestBid
        }
        val price = raw ?: mid ?: return null
        val slippageBps = decision.maxSlippageBps ?: return price
        if (mid == null || mid <= 0.0) return price
        val limit = if (side == OrderSide.BUY) {
            mid * (1.0 + slippageBps / 10_000.0)
        } else {
            mid * (1.0 - slippageBps / 10_000.0)
        }
        return if (side == OrderSide.BUY) {
            if (price <= limit) price else null
        } else {
            if (price >= limit) price else null
        }
    }

    private data class TtlOrder(
        val symbol: Symbol,
        val expireAtMs: Long
    )
}
