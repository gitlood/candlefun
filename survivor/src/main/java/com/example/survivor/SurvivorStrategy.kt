package com.example.survivor

import com.example.account.domain.Price
import com.example.account.domain.Qty
import com.example.account.domain.Symbol
import com.example.execution.domain.ExecutionGateway
import com.example.execution.domain.OrderCancelRequest
import com.example.execution.domain.OrderRequest
import com.example.execution.domain.TimeInForce
import com.example.platform.model.enums.OrderSide
import com.example.platform.model.enums.OrderType
import com.example.platform.report.Telemetry
import com.example.survivor.util.RollingOiWindow
import kotlin.math.abs

class SurvivorStrategy(
    private val gateway: ExecutionGateway,
    private val config: SurvivorConfig,
    private val kpiTracker: SurvivorKpiTracker,
    private val gateStats: SurvivorGateStats? = null
) {
    private val oiWindow = RollingOiWindow(config.oiWindowMs)
    private var side: SurvivorSide = SurvivorSide.FLAT
    private var entryTimeMs: Long? = null
    private var activeOrderId: Long? = null
    private var activeOrderMs: Long? = null
    private var nextFundingTimeMs: Long? = null

    suspend fun onSnapshot(snapshot: SurvivorSnapshot) {
        if (snapshot.symbol != config.symbol) return
        gateStats?.total = gateStats?.total?.plus(1) ?: 0
        val now = snapshot.timestampMs
        oiWindow.add(now, snapshot.openInterest)
        syncPosition(now)
        kpiTracker.onMark(snapshot, side)
        updateFundingTimestamp(snapshot.nextFundingTimeMs)
        cancelStaleOrders(now)

        val regimeOk = regimeOk(snapshot)
        val desired = desiredSide(snapshot)
        Telemetry.emit(
            type = "strategy_signal",
            tsMs = now,
            data = mapOf(
                "strategy_id" to "survivor",
                "symbol" to config.symbol,
                "funding_rate" to snapshot.fundingRate,
                "basis_pct" to snapshot.basisPct,
                "volatility" to snapshot.volatility,
                "spread_pct" to snapshot.spreadPct,
                "open_interest" to snapshot.openInterest,
                "regime_ok" to regimeOk,
                "desired_side" to desired.name
            )
        )
        if (!regimeOk) return

        if (side == SurvivorSide.FLAT && desired == SurvivorSide.FLAT) {
            gateStats?.flatFunding = gateStats?.flatFunding?.plus(1) ?: 0
            return
        }
        if (side == SurvivorSide.FLAT && desired != SurvivorSide.FLAT) {
            enter(desired, snapshot)
        } else if (side != SurvivorSide.FLAT) {
            if (shouldExit(snapshot)) {
                exit(snapshot)
            }
        }
    }

    private fun regimeOk(snapshot: SurvivorSnapshot): Boolean {
        if (snapshot.volatility > config.maxVolatility) {
            gateStats?.rejectedVol = gateStats?.rejectedVol?.plus(1) ?: 0
            return false
        }
        if (snapshot.spreadPct > config.maxSpreadPct) {
            gateStats?.rejectedSpread = gateStats?.rejectedSpread?.plus(1) ?: 0
            return false
        }
        if (oiWindow.isJumping(snapshot.openInterest, config.maxOiJumpPct)) {
            gateStats?.rejectedOi = gateStats?.rejectedOi?.plus(1) ?: 0
            return false
        }
        return true
    }

    private fun desiredSide(snapshot: SurvivorSnapshot): SurvivorSide {
        return when {
            snapshot.fundingRate >= config.entryFundingThreshold -> SurvivorSide.SHORT_PERP
            snapshot.fundingRate <= -config.entryFundingThreshold -> SurvivorSide.LONG_PERP
            else -> SurvivorSide.FLAT
        }
    }

    private fun shouldExit(snapshot: SurvivorSnapshot): Boolean {
        val entryMs = entryTimeMs ?: return false
        if (abs(snapshot.fundingRate) <= config.exitFundingThreshold) return true
        if (abs(snapshot.basisPct) >= config.basisStopAbsPct) return true
        if (snapshot.volatility > config.maxVolatility) return true
        if (snapshot.timestampMs - entryMs >= config.maxHoldMs) return true
        return false
    }

    private suspend fun enter(desired: SurvivorSide, snapshot: SurvivorSnapshot) {
        cancelOpenOrders()
        val orderSide = if (desired == SurvivorSide.LONG_PERP) OrderSide.BUY else OrderSide.SELL
        val price = snapshot.markPrice
        val qty = config.orderQty
        val signedQty = if (orderSide == OrderSide.BUY) qty else -qty
        Telemetry.emit(
            type = "strategy_intent",
            tsMs = snapshot.timestampMs,
            data = mapOf(
                "strategy_id" to "survivor",
                "symbol" to config.symbol,
                "desired_delta" to signedQty,
                "urgency" to "LOW",
                "prefer_maker" to true,
                "ttl_ms" to config.orderTtlMs,
                "limit_price" to price,
                "reason" to "funding_entry"
            )
        )
        val request = OrderRequest(
            symbol = Symbol.of(config.symbol),
            side = orderSide,
            type = OrderType.LIMIT,
            quantity = Qty.fromDouble(qty),
            price = Price.fromDouble(price),
            timeInForce = TimeInForce.GTC,
            clientOrderId = "survivor_${config.symbol}_${orderSide.name}_${snapshot.timestampMs}"
        )
        val order = gateway.placeOrder(request)
        activeOrderId = order.orderId
        activeOrderMs = snapshot.timestampMs
        side = desired
        entryTimeMs = snapshot.timestampMs
        kpiTracker.onOrderPlaced(order.orderId, snapshot, desired)
        gateStats?.entries = gateStats?.entries?.plus(1) ?: 0
        if (config.logSignals) {
            println("survivor enter=${config.symbol} side=$desired price=$price qty=$qty funding=${snapshot.fundingRate}")
        }
    }

    private suspend fun exit(snapshot: SurvivorSnapshot) {
        cancelOpenOrders()
        val orderSide = if (side == SurvivorSide.LONG_PERP) OrderSide.SELL else OrderSide.BUY
        val price = snapshot.markPrice
        val qty = config.orderQty
        val signedQty = if (orderSide == OrderSide.BUY) qty else -qty
        Telemetry.emit(
            type = "strategy_intent",
            tsMs = snapshot.timestampMs,
            data = mapOf(
                "strategy_id" to "survivor",
                "symbol" to config.symbol,
                "desired_delta" to signedQty,
                "urgency" to "HIGH",
                "prefer_maker" to true,
                "ttl_ms" to config.orderTtlMs,
                "limit_price" to price,
                "reason" to "funding_exit"
            )
        )
        val request = OrderRequest(
            symbol = Symbol.of(config.symbol),
            side = orderSide,
            type = OrderType.LIMIT,
            quantity = Qty.fromDouble(qty),
            price = Price.fromDouble(price),
            timeInForce = TimeInForce.GTC,
            clientOrderId = "survivor_exit_${config.symbol}_${orderSide.name}_${snapshot.timestampMs}"
        )
        val order = gateway.placeOrder(request)
        activeOrderId = order.orderId
        activeOrderMs = snapshot.timestampMs
        kpiTracker.onOrderPlaced(order.orderId, snapshot, SurvivorSide.FLAT)
        gateStats?.exits = gateStats?.exits?.plus(1) ?: 0
        if (config.logSignals) {
            println("survivor exit=${config.symbol} side=$orderSide price=$price qty=$qty")
        }
        side = SurvivorSide.FLAT
        entryTimeMs = null
    }

    private suspend fun syncPosition(nowMs: Long) {
        val symbol = Symbol.of(config.symbol)
        val qty = gateway.getPositions().firstOrNull { it.symbol == symbol }?.quantity?.toDouble() ?: 0.0
        val nextSide = when {
            qty > 0.0 -> SurvivorSide.LONG_PERP
            qty < 0.0 -> SurvivorSide.SHORT_PERP
            else -> SurvivorSide.FLAT
        }
        if (nextSide != side) {
            side = nextSide
            entryTimeMs = if (side == SurvivorSide.FLAT) null else nowMs
        }
    }

    private fun updateFundingTimestamp(nextTime: Long) {
        if (nextFundingTimeMs == null || nextTime > nextFundingTimeMs!!) {
            nextFundingTimeMs = nextTime
        }
    }

    private suspend fun cancelOpenOrders() {
        val symbol = Symbol.of(config.symbol)
        val open = gateway.getOpenOrders(symbol)
        for (order in open) {
            gateway.cancelOrder(OrderCancelRequest(symbol = symbol, orderId = order.orderId))
            kpiTracker.onOrderCanceled(order.orderId, stale = false)
        }
        activeOrderId = null
        activeOrderMs = null
    }

    private suspend fun cancelStaleOrders(nowMs: Long) {
        val orderId = activeOrderId ?: return
        val placed = activeOrderMs ?: return
        if (config.orderTtlMs <= 0L) return
        if (nowMs - placed < config.orderTtlMs) return
        val symbol = Symbol.of(config.symbol)
        gateway.cancelOrder(OrderCancelRequest(symbol = symbol, orderId = orderId))
        kpiTracker.onOrderCanceled(orderId, stale = true)
        activeOrderId = null
        activeOrderMs = null
    }
}
