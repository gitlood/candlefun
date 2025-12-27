package com.example.ofi.kukanov

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
import com.example.platform.report.Telemetry
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

class OfiKukanovStrategy(
    private val gateway: ExecutionGateway,
    private val config: OfiStrategyConfig,
    signalConfig: KukanovOfiConfig = KukanovOfiConfig(),
    private val kpiSink: OfiKpiSink? = null
) {
    private val accumulator = KukanovOfiAccumulator(signalConfig)
    private val spreadWindow = RollingAverageWindow(config.spreadWindowMs)
    private var lastActionMs = 0L
    private var entryTimeMs: Long? = null
    private var lastPositionQty = 0.0
    private var activeOrderId: Long? = null
    private var activeOrderPlacedMs: Long? = null
    private var activeOrderStyle: OfiOrderStyle? = null

    suspend fun onMarketState(state: MarketState) {
        if (state.symbol != config.symbol) return
        val signal = accumulator.update(state)
        val now = signal.eventTimeMs ?: signal.timestampMs
        val spreadPct = spreadPct(signal)
        if (spreadPct != null) spreadWindow.add(now, spreadPct)

        cancelStaleOrders(now)

        val normalized = signal.normalizedOfi ?: return
        val posQty = positionQty()
        updateEntryTime(posQty, now)

        val spreadOk = isSpreadStable(spreadPct, now)
        val depthOk = isDepthHealthy(signal)
        val tradeOk = isTradeConfirmed(state, normalized)
        Telemetry.emit(
            type = "strategy_signal",
            tsMs = now,
            data = mapOf(
                "strategy_id" to "ofi_kukanov",
                "symbol" to config.symbol,
                "normalized_ofi" to normalized,
                "spread_pct" to spreadPct,
                "depth_notional" to signal.depthNotional,
                "depth_qty" to signal.depthQty,
                "trade_count_1s" to state.tradeCount1s,
                "trade_imbalance_1s" to state.tradeImbalance1s,
                "spread_ok" to spreadOk,
                "depth_ok" to depthOk,
                "trade_ok" to tradeOk
            )
        )

        if (now - lastActionMs < config.minSignalIntervalMs) return

        if (posQty == 0.0) {
            if (!spreadOk) return
            if (!depthOk) return
            if (!tradeOk) return
            if (abs(normalized) >= config.entryThreshold) {
                val side = if (normalized > 0.0) OrderSide.BUY else OrderSide.SELL
                val style = resolveEntryStyle(normalized)
                placeEntryOrder(side, state, now, style, signal)
            }
        } else if (shouldExit(posQty, normalized, now)) {
            closePosition(posQty, state, now)
        }
    }

    private fun shouldExit(posQty: Double, normalized: Double, nowMs: Long): Boolean {
        val entryMs = entryTimeMs ?: return false
        if (nowMs - entryMs >= config.maxHoldMs) return true
        if (abs(normalized) <= config.exitThreshold) return true
        val posDir = direction(posQty)
        val ofiDir = direction(normalized)
        return ofiDir != 0 && ofiDir != posDir
    }

    private suspend fun placeEntryOrder(
        side: OrderSide,
        state: MarketState,
        nowMs: Long,
        style: OfiOrderStyle,
        signal: OfiSignal
    ) {
        val price = resolvePrice(side, state, style) ?: return
        val qty = roundDown(config.orderQty, config.qtyStep)
        if (qty <= 0.0) return
        cancelOpenOrders()
        val signedQty = if (side == OrderSide.BUY) qty else -qty
        Telemetry.emit(
            type = "strategy_intent",
            tsMs = nowMs,
            data = mapOf(
                "strategy_id" to "ofi_kukanov",
                "symbol" to config.symbol,
                "desired_delta" to signedQty,
                "urgency" to "MEDIUM",
                "prefer_maker" to (style == OfiOrderStyle.JOIN),
                "ttl_ms" to if (style == OfiOrderStyle.TAKE) config.takeOrderTtlMs else config.orderTtlMs,
                "limit_price" to price,
                "reason" to "ofi_entry"
            )
        )
        val request = OrderRequest(
            symbol = Symbol.of(config.symbol),
            side = side,
            type = OrderType.LIMIT,
            quantity = Qty.fromDouble(qty),
            price = Price.fromDouble(price),
            timeInForce = TimeInForce.GTC,
            clientOrderId = "ofi_${config.symbol}_${side.name}_$nowMs"
        )
        val order = gateway.placeOrder(request)
        trackActiveOrder(order.orderId, nowMs, style)
        kpiSink?.onOrderPlaced(
            OfiOrderMeta(
                orderId = order.orderId,
                symbol = config.symbol,
                side = side,
                style = style,
                expectedMid = resolveMid(state),
                bestBid = state.bestBidPrice,
                bestAsk = state.bestAskPrice,
                normalizedOfi = signal.normalizedOfi,
                timestampMs = nowMs
            )
        )
        lastActionMs = nowMs
        if (config.logSignals) {
            println("ofi entry=${config.symbol} side=${side.name} style=${style.name} price=$price qty=$qty")
        }
    }

    private suspend fun closePosition(posQty: Double, state: MarketState, nowMs: Long) {
        val side = if (posQty > 0.0) OrderSide.SELL else OrderSide.BUY
        val price = resolvePrice(side, state, config.orderStyle) ?: return
        val qty = roundDown(abs(posQty), config.qtyStep)
        if (qty <= 0.0) return
        cancelOpenOrders()
        val signedQty = if (side == OrderSide.BUY) qty else -qty
        Telemetry.emit(
            type = "strategy_intent",
            tsMs = nowMs,
            data = mapOf(
                "strategy_id" to "ofi_kukanov",
                "symbol" to config.symbol,
                "desired_delta" to signedQty,
                "urgency" to "HIGH",
                "prefer_maker" to (config.orderStyle == OfiOrderStyle.JOIN),
                "ttl_ms" to if (config.orderStyle == OfiOrderStyle.TAKE) config.takeOrderTtlMs else config.orderTtlMs,
                "limit_price" to price,
                "reason" to "ofi_exit"
            )
        )
        val request = OrderRequest(
            symbol = Symbol.of(config.symbol),
            side = side,
            type = OrderType.LIMIT,
            quantity = Qty.fromDouble(qty),
            price = Price.fromDouble(price),
            timeInForce = TimeInForce.GTC,
            clientOrderId = "ofi_exit_${config.symbol}_${side.name}_$nowMs"
        )
        val order = gateway.placeOrder(request)
        trackActiveOrder(order.orderId, nowMs, config.orderStyle)
        kpiSink?.onOrderPlaced(
            OfiOrderMeta(
                orderId = order.orderId,
                symbol = config.symbol,
                side = side,
                style = config.orderStyle,
                expectedMid = resolveMid(state),
                bestBid = state.bestBidPrice,
                bestAsk = state.bestAskPrice,
                normalizedOfi = null,
                timestampMs = nowMs
            )
        )
        lastActionMs = nowMs
        if (config.logSignals) {
            println("ofi exit=${config.symbol} side=${side.name} price=$price qty=$qty")
        }
    }

    private suspend fun positionQty(): Double {
        val symbol = Symbol.of(config.symbol)
        return gateway.getPositions()
            .firstOrNull { it.symbol == symbol }
            ?.quantity
            ?.toDouble()
            ?: 0.0
    }

    private fun updateEntryTime(posQty: Double, nowMs: Long) {
        if (lastPositionQty == 0.0 && posQty != 0.0) {
            entryTimeMs = nowMs
        }
        if (posQty == 0.0) {
            entryTimeMs = null
        }
        lastPositionQty = posQty
    }

    private fun spreadPct(signal: OfiSignal): Double? {
        val spread = signal.spread ?: return null
        val mid = signal.midPrice ?: return null
        if (mid <= 0.0) return null
        return spread / mid
    }

    private fun isDepthHealthy(signal: OfiSignal): Boolean {
        val minNotional = config.minDepthNotional
        if (minNotional != null && signal.depthNotional < minNotional) return false
        val minQty = config.minDepthQty
        if (minQty != null && signal.depthQty < minQty) return false
        return true
    }

    private fun isTradeConfirmed(state: MarketState, normalizedOfi: Double): Boolean {
        if (!config.useTradeConfirm) return true
        if (state.tradeCount1s < config.minTradeCount1s) return false
        val tradeImb = state.tradeImbalance1s
        if (abs(tradeImb) < config.minTradeImbalance1s) return false
        val tradeDir = direction(tradeImb)
        val ofiDir = direction(normalizedOfi)
        return tradeDir == ofiDir
    }

    private fun isSpreadStable(spreadPct: Double?, nowMs: Long): Boolean {
        if (spreadPct == null) return false
        val maxSpread = config.maxSpreadPct
        if (maxSpread != null && spreadPct > maxSpread) return false
        val avg = spreadWindow.mean(nowMs) ?: return false
        if (spreadWindow.count(nowMs) < config.minSpreadSamples) return false
        if (avg <= 0.0) return false
        val deviation = abs(spreadPct - avg) / avg
        return deviation <= config.maxSpreadDeviationPct
    }

    private suspend fun cancelOpenOrders() {
        val symbol = Symbol.of(config.symbol)
        val open = gateway.getOpenOrders(symbol)
        for (order in open) {
            gateway.cancelOrder(OrderCancelRequest(symbol = symbol, orderId = order.orderId))
            kpiSink?.onOrderCanceled(order.orderId, stale = false)
        }
        clearActiveOrder()
    }

    private suspend fun cancelStaleOrders(nowMs: Long) {
        val orderId = activeOrderId ?: return
        val placedMs = activeOrderPlacedMs ?: return
        val style = activeOrderStyle ?: OfiOrderStyle.JOIN
        val ttl = if (style == OfiOrderStyle.TAKE) config.takeOrderTtlMs else config.orderTtlMs
        if (ttl <= 0L) return
        if (nowMs - placedMs < ttl) return
        val symbol = Symbol.of(config.symbol)
        gateway.cancelOrder(OrderCancelRequest(symbol = symbol, orderId = orderId))
        kpiSink?.onOrderCanceled(orderId, stale = true)
        clearActiveOrder()
    }

    private fun resolvePrice(
        side: OrderSide,
        state: MarketState,
        style: OfiOrderStyle
    ): Double? {
        val bestBid = state.bestBidPrice
        val bestAsk = state.bestAskPrice
        val raw = when (style) {
            OfiOrderStyle.JOIN -> {
                val offset = config.joinOffsetTicks * config.priceTick
                if (side == OrderSide.BUY) bestBid?.plus(offset) else bestAsk?.minus(offset)
            }
            OfiOrderStyle.TAKE -> if (side == OrderSide.BUY) bestAsk else bestBid
        } ?: return null
        return roundPrice(raw, side, style)
    }

    private fun resolveMid(state: MarketState): Double? {
        val mid = state.midPrice
        if (mid != null) return mid
        val bid = state.bestBidPrice ?: return null
        val ask = state.bestAskPrice ?: return null
        return (bid + ask) / 2.0
    }

    private fun roundPrice(price: Double, side: OrderSide, style: OfiOrderStyle): Double {
        val tick = config.priceTick
        if (tick <= 0.0) return price
        return when (style) {
            OfiOrderStyle.JOIN -> {
                if (side == OrderSide.BUY) roundDown(price, tick) else roundUp(price, tick)
            }
            OfiOrderStyle.TAKE -> {
                if (side == OrderSide.BUY) roundUp(price, tick) else roundDown(price, tick)
            }
        }
    }

    private fun roundDown(value: Double, step: Double): Double {
        if (step <= 0.0) return value
        return floor(value / step) * step
    }

    private fun roundUp(value: Double, step: Double): Double {
        if (step <= 0.0) return value
        return ceil(value / step) * step
    }

    private fun direction(value: Double): Int {
        return when {
            value > 0.0 -> 1
            value < 0.0 -> -1
            else -> 0
        }
    }

    private fun resolveEntryStyle(normalized: Double): OfiOrderStyle {
        if (config.orderStyle == OfiOrderStyle.TAKE) return OfiOrderStyle.TAKE
        val takeThreshold = config.entryThreshold * config.takeThresholdMultiplier
        if (abs(normalized) < takeThreshold) return OfiOrderStyle.JOIN
        val edgeBps = abs(normalized) * 10_000.0
        return if (edgeBps >= config.takeMinEdgeBps) OfiOrderStyle.TAKE else OfiOrderStyle.JOIN
    }

    private fun trackActiveOrder(orderId: Long, nowMs: Long, style: OfiOrderStyle) {
        activeOrderId = orderId
        activeOrderPlacedMs = nowMs
        activeOrderStyle = style
    }

    private fun clearActiveOrder() {
        activeOrderId = null
        activeOrderPlacedMs = null
        activeOrderStyle = null
    }
}

data class OfiOrderMeta(
    val orderId: Long,
    val symbol: String,
    val side: OrderSide,
    val style: OfiOrderStyle,
    val expectedMid: Double?,
    val bestBid: Double?,
    val bestAsk: Double?,
    val normalizedOfi: Double?,
    val timestampMs: Long
)

interface OfiKpiSink {
    fun onOrderPlaced(meta: OfiOrderMeta)
    fun onOrderCanceled(orderId: Long, stale: Boolean)
}

private class RollingAverageWindow(windowMs: Long) {
    private val windowMs = windowMs
    private val samples = ArrayDeque<TimedSample>(128)
    private var sum = 0.0

    fun add(timestampMs: Long, value: Double) {
        samples.addLast(TimedSample(timestampMs, value))
        sum += value
        trim(timestampMs)
    }

    fun mean(timestampMs: Long): Double? {
        trim(timestampMs)
        if (samples.isEmpty()) return null
        return sum / samples.size
    }

    fun count(timestampMs: Long): Int {
        trim(timestampMs)
        return samples.size
    }

    private fun trim(nowMs: Long) {
        while (samples.isNotEmpty() && samples.first().timestampMs < nowMs - windowMs) {
            val sample = samples.removeFirst()
            sum -= sample.value
        }
    }

    private data class TimedSample(val timestampMs: Long, val value: Double)
}
