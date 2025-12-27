package com.example.vacuum

import com.example.account.domain.Price
import com.example.account.domain.Qty
import com.example.account.domain.Symbol
import com.example.execution.domain.ExecutionGateway
import com.example.execution.domain.OrderCancelRequest
import com.example.execution.domain.OrderRequest
import com.example.execution.domain.TimeInForce
import com.example.platform.model.BookLevel
import com.example.platform.model.MarketState
import com.example.platform.model.enums.OrderSide
import com.example.platform.model.enums.OrderType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class VacuumStrategy(
    private val gateway: ExecutionGateway,
    private val config: VacuumConfig,
    private val kpi: VacuumKpiTracker
) {
    private val depthWindow = RollingMaxWindow(config.depthWindowMs)
    private val spreadWindow = RollingAverageWindow(config.spreadWindowMs)
    private var lastActionMs = 0L
    private var activeOrderId: Long? = null
    private var activeOrderMs: Long? = null
    private var positionSide: OrderSide? = null
    private var entryTimeMs: Long? = null
    private var entryMid: Double? = null
    private var peakFavorableMid: Double? = null
    private var pausedUntilMs: Long = 0L

    suspend fun onMarketState(state: MarketState) {
        if (state.symbol != config.symbol) return
        val now = state.eventTimeMs ?: state.timestampMs
        cancelStaleOrders(now)
        kpi.onMarketState(state.symbol, state.midPrice ?: state.microPrice, now)
        syncPosition(now)

        val signal = buildSignal(state, now) ?: return
        if (now < pausedUntilMs) return

        if (positionSide == null) {
            if (now - lastActionMs < config.entryCooldownMs) return
            if (shouldEnter(signal)) {
                val side = if (signal.tradeImbalance > 0.0) OrderSide.BUY else OrderSide.SELL
                enter(side, state, now, signal)
            }
        } else {
            updateTrailing(state)
            if (shouldExit(state, signal, now)) {
                exit(state, now)
            }
        }

        if (shouldPause(now)) {
            pausedUntilMs = now + config.pauseMs
        }
    }

    private fun buildSignal(state: MarketState, now: Long): VacuumSignal? {
        val depthNotional = depthNotional(state.bidLevels, state.askLevels)
        if (depthNotional <= 0.0) return null
        depthWindow.add(now, depthNotional)
        val maxDepth = depthWindow.max(now) ?: depthNotional
        val depthDropPct = if (maxDepth > 0.0) (maxDepth - depthNotional) / maxDepth else 0.0

        val mid = state.midPrice ?: state.microPrice
        val spread = state.spread
        if (mid == null || mid <= 0.0 || spread == null) return null
        val spreadPct = spread / mid
        spreadWindow.add(now, spreadPct)

        return VacuumSignal(
            symbol = state.symbol,
            timestampMs = now,
            depthNotional = depthNotional,
            depthDropPct = depthDropPct,
            spreadPct = spreadPct,
            tradeCount = state.tradeCount1s,
            tradeImbalance = state.tradeImbalance1s
        )
    }

    private fun shouldEnter(signal: VacuumSignal): Boolean {
        if (signal.depthDropPct < config.depthDropPct) return false
        if (signal.spreadPct > config.maxSpreadPct) return false
        val avgSpread = spreadWindow.mean(signal.timestampMs) ?: return false
        val widened = signal.spreadPct >= avgSpread * (1.0 + config.spreadWidenPct)
        if (!widened) return false
        if (signal.tradeCount < config.minTradeCount1s) return false
        if (abs(signal.tradeImbalance) < config.minTradeImbalance1s) return false
        return true
    }

    private fun shouldExit(state: MarketState, signal: VacuumSignal, now: Long): Boolean {
        val entry = entryTimeMs ?: return false
        if (now - entry >= config.maxHoldMs) return true
        if (signal.depthDropPct <= config.depthRefillPct) return true
        val trail = trailingStopTriggered(state) ?: false
        return trail
    }

    private suspend fun enter(side: OrderSide, state: MarketState, now: Long, signal: VacuumSignal) {
        cancelOpenOrders()
        val price = aggressivePrice(side, state) ?: return
        val qty = roundDown(config.orderQty, config.qtyStep)
        if (qty <= 0.0) return
        val order = gateway.placeOrder(
            OrderRequest(
                symbol = Symbol.of(config.symbol),
                side = side,
                type = OrderType.LIMIT,
                quantity = Qty.fromDouble(qty),
                price = Price.fromDouble(price),
                timeInForce = TimeInForce.GTC,
                clientOrderId = "vacuum_${config.symbol}_${side.name}_$now"
            )
        )
        activeOrderId = order.orderId
        activeOrderMs = now
        lastActionMs = now
        entryTimeMs = now
        entryMid = state.midPrice ?: state.microPrice
        peakFavorableMid = entryMid
        kpi.onOrderPlaced(
            VacuumOrderMeta(
                orderId = order.orderId,
                symbol = config.symbol,
                side = side,
                expectedMid = entryMid,
                bestBid = state.bestBidPrice,
                bestAsk = state.bestAskPrice,
                timestampMs = now
            )
        )
        if (config.logSignals) {
            println("vacuum entry=${config.symbol} side=${side.name} drop=${"%.3f".format(signal.depthDropPct)}")
        }
    }

    private suspend fun exit(state: MarketState, now: Long) {
        cancelOpenOrders()
        val side = if (positionSide == OrderSide.BUY) OrderSide.SELL else OrderSide.BUY
        val price = aggressivePrice(side, state) ?: return
        val qty = currentPositionQty()
        if (qty <= 0.0) return
        val order = gateway.placeOrder(
            OrderRequest(
                symbol = Symbol.of(config.symbol),
                side = side,
                type = OrderType.LIMIT,
                quantity = Qty.fromDouble(qty),
                price = Price.fromDouble(price),
                timeInForce = TimeInForce.GTC,
                clientOrderId = "vacuum_exit_${config.symbol}_${side.name}_$now"
            )
        )
        activeOrderId = order.orderId
        activeOrderMs = now
        lastActionMs = now
        entryTimeMs = null
        entryMid = null
        peakFavorableMid = null
        if (config.logSignals) {
            println("vacuum exit=${config.symbol} side=${side.name}")
        }
    }

    private fun updateTrailing(state: MarketState) {
        val mid = state.midPrice ?: state.microPrice ?: return
        val peak = peakFavorableMid
        val side = positionSide ?: return
        val nextPeak = if (side == OrderSide.BUY) {
            max(peak ?: mid, mid)
        } else {
            min(peak ?: mid, mid)
        }
        peakFavorableMid = nextPeak
    }

    private fun trailingStopTriggered(state: MarketState): Boolean? {
        val mid = state.midPrice ?: state.microPrice ?: return null
        val peak = peakFavorableMid ?: return null
        val side = positionSide ?: return null
        val drawdown = if (side == OrderSide.BUY) {
            (peak - mid) / peak
        } else {
            (mid - peak) / peak
        }
        return drawdown * 10_000.0 >= config.trailingStopBps
    }

    private fun depthNotional(bids: List<BookLevel>, asks: List<BookLevel>): Double {
        val topBids = bids.take(config.depthLevels)
        val topAsks = asks.take(config.depthLevels)
        var sum = 0.0
        for (lvl in topBids) sum += lvl.price * lvl.quantity
        for (lvl in topAsks) sum += lvl.price * lvl.quantity
        return sum
    }

    private fun aggressivePrice(side: OrderSide, state: MarketState): Double? {
        val bestBid = state.bestBidPrice
        val bestAsk = state.bestAskPrice
        return when (side) {
            OrderSide.BUY -> bestAsk
            OrderSide.SELL -> bestBid
        }
    }

    private suspend fun cancelOpenOrders() {
        val symbol = Symbol.of(config.symbol)
        val open = gateway.getOpenOrders(symbol)
        for (order in open) {
            gateway.cancelOrder(OrderCancelRequest(symbol = symbol, orderId = order.orderId))
            kpi.onOrderCanceled(order.orderId, stale = false)
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
        kpi.onOrderCanceled(orderId, stale = true)
        activeOrderId = null
        activeOrderMs = null
    }

    private suspend fun syncPosition(nowMs: Long) {
        val symbol = Symbol.of(config.symbol)
        val pos = gateway.getPositions().firstOrNull { it.symbol == symbol }
        val qty = pos?.quantity?.toDouble() ?: 0.0
        val side = when {
            qty > 0.0 -> OrderSide.BUY
            qty < 0.0 -> OrderSide.SELL
            else -> null
        }
        if (side != positionSide) {
            positionSide = side
            entryTimeMs = if (side == null) null else nowMs
        }
    }

    private suspend fun currentPositionQty(): Double {
        val symbol = Symbol.of(config.symbol)
        val qty = gateway.getPositions().firstOrNull { it.symbol == symbol }?.quantity?.toDouble() ?: 0.0
        return abs(qty)
    }

    private fun shouldPause(nowMs: Long): Boolean {
        val slip = kpi.lastSlippageBps() ?: return false
        if (slip > config.slippagePauseBps) return true
        if (kpi.tailLossCount() >= config.maxTailLosses) return true
        return false
    }

    private fun roundDown(value: Double, step: Double): Double {
        if (step <= 0.0) return value
        return kotlin.math.floor(value / step) * step
    }
}

private class RollingMaxWindow(windowMs: Long) {
    private val windowMs = windowMs
    private val values = ArrayDeque<TimedDouble>(64)

    fun add(timestampMs: Long, value: Double) {
        values.addLast(TimedDouble(timestampMs, value))
        trim(timestampMs)
    }

    fun max(timestampMs: Long): Double? {
        trim(timestampMs)
        if (values.isEmpty()) return null
        var m = Double.NEGATIVE_INFINITY
        for (v in values) {
            if (v.value > m) m = v.value
        }
        return if (m == Double.NEGATIVE_INFINITY) null else m
    }

    private fun trim(nowMs: Long) {
        while (values.isNotEmpty() && values.first().timestampMs < nowMs - windowMs) {
            values.removeFirst()
        }
    }

    private data class TimedDouble(val timestampMs: Long, val value: Double)
}

private class RollingAverageWindow(windowMs: Long) {
    private val windowMs = windowMs
    private val values = ArrayDeque<TimedDouble>(64)
    private var sum = 0.0

    fun add(timestampMs: Long, value: Double) {
        values.addLast(TimedDouble(timestampMs, value))
        sum += value
        trim(timestampMs)
    }

    fun mean(timestampMs: Long): Double? {
        trim(timestampMs)
        if (values.isEmpty()) return null
        return sum / values.size
    }

    private fun trim(nowMs: Long) {
        while (values.isNotEmpty() && values.first().timestampMs < nowMs - windowMs) {
            val v = values.removeFirst()
            sum -= v.value
        }
    }

    private data class TimedDouble(val timestampMs: Long, val value: Double)
}
