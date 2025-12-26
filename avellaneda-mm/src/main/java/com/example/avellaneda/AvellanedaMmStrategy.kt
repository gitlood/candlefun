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

    suspend fun onMarketState(state: MarketState) {
        if (state.symbol != config.symbol) return
        val now = state.eventTimeMs ?: state.timestampMs
        if (now - lastActionMs < config.quoteRefreshMs) return

        val mid = state.midPrice ?: state.microPrice ?: return
        val spread = state.spread ?: return
        if (spread <= 0.0) return
        val spreadPct = spread / mid
        val gateReason = gateReason(state, spreadPct)
        if (gateReason != null) {
            if (config.logGateDecisions && gateReason != lastGateReason) {
                println("gate=${config.symbol} reason=$gateReason")
                lastGateReason = gateReason
            }
            cancelAll()
            lastActionMs = now
            return
        }
        lastGateReason = null

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

    private fun gateReason(state: MarketState, spreadPct: Double): String? {
        if (spreadPct < config.minSpreadPct) return "spread_below_min"
        val maxSpread = config.maxSpreadPct
        if (maxSpread != null && spreadPct > maxSpread) return "spread_too_wide"

        val imbalanceLimit = config.maxDepthImbalance
        val imbalance = state.depthImbalance
        if (imbalanceLimit != null && imbalance != null && abs(imbalance) > imbalanceLimit) {
            return "depth_imbalance"
        }

        val minTopDepth = config.minTopDepth
        if (minTopDepth != null) {
            val bidDepth = state.bidLevels.sumOf { it.quantity }
            val askDepth = state.askLevels.sumOf { it.quantity }
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
        val placed = gateway.placeOrder(
            OrderRequest(
                symbol = Symbol.of(config.symbol),
                side = side,
                type = OrderType.LIMIT,
                quantity = Qty.fromString(qtyStr),
                price = Price.fromString(priceStr),
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
