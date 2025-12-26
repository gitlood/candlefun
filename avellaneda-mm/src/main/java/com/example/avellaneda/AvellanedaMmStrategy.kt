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
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs

class AvellanedaMmStrategy(
    private val gateway: ExecutionGateway,
    private val config: AvellanedaMmConfig
) {
    private var lastActionMs: Long = 0L
    private var lastBidOrderId: Long? = null
    private var lastAskOrderId: Long? = null
    private var lastGateReason: String? = null
    private var lastGateMs: Long = 0L
    private val spreadSamples = ArrayDeque<SpreadSample>(256)

    suspend fun onMarketState(state: MarketState) {
        if (state.symbol != config.symbol) return
        val now = state.eventTimeMs ?: state.timestampMs
        if (now - lastActionMs < config.quoteRefreshMs) return

        val mid = state.midPrice ?: state.microPrice ?: return
        val spread = state.spread ?: return
        if (spread <= 0.0) return
        val spreadPct = spread / mid
        addSpreadSample(now, spreadPct)
        val gateReason = gateReason(state, spreadPct, now)
        if (gateReason != null) {
            if (config.logGateDecisions && gateReason != lastGateReason) {
                println("gate=${config.symbol} reason=$gateReason")
                lastGateReason = gateReason
            }
            lastGateMs = now
            cancelAll()
            lastActionMs = now
            return
        }
        lastGateReason = null
        if (now - lastGateMs < config.gateCooldownMs) {
            if (config.logGateDecisions && lastGateReason != "cooldown") {
                println("gate=${config.symbol} reason=cooldown")
                lastGateReason = "cooldown"
            }
            cancelAll()
            lastActionMs = now
            return
        }

        val positionQty = gateway.getPositions()
            .firstOrNull { it.symbol.value == config.symbol }
            ?.quantity
            ?.toDouble()
            ?: 0.0
        val inventoryFraction = if (config.maxInventory > 0.0) {
            (positionQty / config.maxInventory).coerceIn(-1.0, 1.0)
        } else {
            0.0
        }
        val skew = inventoryFraction * config.inventorySkew * mid

        val minHalfSpread = (config.minSpreadPct * mid) / 2.0
        val baseHalfSpread = maxOf(spread / 2.0, minHalfSpread)
        val volAdj = (state.vol1s ?: 0.0) * config.volSpreadMultiplier * mid
        val halfSpread = baseHalfSpread + volAdj

        var bid = mid - halfSpread - skew
        var ask = mid + halfSpread - skew

        val bestBid = state.bestBidPrice
        val bestAsk = state.bestAskPrice
        when (config.quoteStyle) {
            QuoteStyle.JOIN -> {
                if (bestBid != null) bid = minOf(bid, bestBid)
                if (bestAsk != null) ask = maxOf(ask, bestAsk)
            }
            QuoteStyle.IMPROVE -> {
                if (bestBid != null) bid = maxOf(bid, bestBid + config.priceTick)
                if (bestAsk != null) ask = minOf(ask, bestAsk - config.priceTick)
            }
            QuoteStyle.WIDEN -> {
                if (bestBid != null) bid = minOf(bid, bestBid - config.priceTick)
                if (bestAsk != null) ask = maxOf(ask, bestAsk + config.priceTick)
            }
        }

        bid = roundDown(bid, config.priceTick)
        ask = roundUp(ask, config.priceTick)
        val minWidth = 2.0 * minHalfSpread
        if (ask - bid < minWidth) {
            val midAdj = (bid + ask) / 2.0
            bid = roundDown(midAdj - minHalfSpread, config.priceTick)
            ask = roundUp(midAdj + minHalfSpread, config.priceTick)
        }
        if (bid >= ask) return

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

    private fun gateReason(state: MarketState, spreadPct: Double, nowMs: Long): String? {
        val maxSpread = config.maxSpreadPct
        if (maxSpread != null && spreadPct > maxSpread) return "spread_too_wide"

        val avgSpread = averageSpread(nowMs)
        if (avgSpread != null) {
            val minAvg = config.minAvgSpreadPct
            if (minAvg != null && avgSpread < minAvg) return "spread_avg_below_min"
            val maxAvg = config.maxAvgSpreadPct
            if (maxAvg != null && avgSpread > maxAvg) return "spread_avg_above_max"
        }

        val imbalanceLimit = config.maxDepthImbalance
        val imbalance = state.depthImbalance
        if (imbalanceLimit != null && imbalance != null && abs(imbalance) > imbalanceLimit) {
            return "depth_imbalance"
        }

        val minTopDepth = config.minTopDepth
        if (minTopDepth != null) {
            val levels = config.topDepthLevels
            val bidDepth = state.bidLevels.take(levels).sumOf { it.quantity }
            val askDepth = state.askLevels.take(levels).sumOf { it.quantity }
            if (bidDepth + askDepth < minTopDepth) return "depth_collapse"
        }

        val maxVol1s = config.maxVol1s
        if (maxVol1s != null && (state.vol1s ?: 0.0) > maxVol1s) return "vol_1s"
        val maxVol5s = config.maxVol5s
        if (maxVol5s != null && (state.vol5s ?: 0.0) > maxVol5s) return "vol_5s"
        val maxVol10s = config.maxVol10s
        if (maxVol10s != null && (state.vol10s ?: 0.0) > maxVol10s) return "vol_10s"

        val maxTradeImb = config.maxTradeImbalance1s
        if (maxTradeImb != null && state.tradeCount1s >= config.minTradeCount1sForToxicity) {
            if (abs(state.tradeImbalance1s) > maxTradeImb) return "toxic_flow"
        }
        return null
    }

    private fun addSpreadSample(timestampMs: Long, spreadPct: Double) {
        spreadSamples.addLast(SpreadSample(timestampMs, spreadPct))
        trimSpreadSamples(timestampMs)
    }

    private fun averageSpread(timestampMs: Long): Double? {
        trimSpreadSamples(timestampMs)
        if (spreadSamples.isEmpty()) return null
        var sum = 0.0
        for (s in spreadSamples) sum += s.value
        return sum / spreadSamples.size
    }

    private fun trimSpreadSamples(nowMs: Long) {
        val cutoff = nowMs - config.spreadWindowMs
        while (spreadSamples.isNotEmpty() && spreadSamples.first().timestampMs < cutoff) {
            spreadSamples.removeFirst()
        }
    }

    private data class SpreadSample(val timestampMs: Long, val value: Double)

    private suspend fun ensureOrder(
        side: OrderSide,
        price: Double,
        qty: Double,
        existingId: Long?,
        now: Long
    ): Long {
        val openOrders = gateway.getOpenOrders(Symbol.of(config.symbol)).filter { it.side == side }
        val existing = openOrders.firstOrNull { it.orderId == existingId } ?: openOrders.firstOrNull()
        val stale = existing != null && (now - existing.transactTimeMs) > config.maxQuoteAgeMs
        val priceChanged = existing != null && abs(existing.price.value.toDouble() - price) >= config.priceTick / 2.0

        if (existing != null && !stale && !priceChanged) return existing.orderId

        if (existing != null) {
            gateway.cancelOrder(OrderCancelRequest(Symbol.of(config.symbol), existing.orderId))
        }

        val roundedQty = roundDown(qty, config.qtyStep)
        val finalQty = roundDown(applyMinNotional(roundedQty, price), config.qtyStep)
        val priceStr = formatToStep(price, config.priceTick)
        val qtyStr = formatToStep(finalQty, config.qtyStep)
        val clientOrderId = "mm_${config.symbol}_${side.name}_${now}"
        val placed = gateway.placeOrder(
            OrderRequest(
                symbol = Symbol.of(config.symbol),
                side = side,
                type = OrderType.LIMIT,
                quantity = Qty.fromString(qtyStr),
                price = Price.fromString(priceStr),
                timeInForce = TimeInForce.GTC,
                clientOrderId = clientOrderId
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

    private fun applyMinNotional(qty: Double, price: Double): Double {
        val minNotional = config.minNotional ?: return qty
        if (price <= 0.0) return qty
        val required = minNotional / price
        if (qty >= required) return qty
        return roundUp(required, config.qtyStep)
    }

    private fun formatToStep(value: Double, step: Double): String {
        if (step <= 0.0) return BigDecimal.valueOf(value).toPlainString()
        val stepBd = BigDecimal.valueOf(step).stripTrailingZeros()
        val scale = stepBd.scale().coerceAtLeast(0)
        val units = BigDecimal.valueOf(value).divide(stepBd, 0, RoundingMode.DOWN)
        val rounded = units.multiply(stepBd).setScale(scale, RoundingMode.DOWN)
        return rounded.stripTrailingZeros().toPlainString()
    }
}
