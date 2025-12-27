package com.example.avellaneda

import com.example.account.domain.Price
import com.example.account.domain.Qty
import com.example.account.domain.Symbol
import com.example.avellaneda.gates.RegimeGate
import com.example.avellaneda.quotes.QuoteCalculator
import com.example.execution.domain.ExecutionGateway
import com.example.execution.domain.OrderCancelRequest
import com.example.execution.domain.OrderRequest
import com.example.execution.domain.TimeInForce
import com.example.platform.model.MarketState
import com.example.platform.model.enums.OrderSide
import com.example.platform.model.enums.OrderType
import com.example.platform.report.Telemetry
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.max

class AvellanedaMmStrategy(
    private val gateway: ExecutionGateway,
    private val config: AvellanedaMmConfig,
    private val adverseBpsProvider: ((String) -> Double?)? = null
) {
    private var lastActionMs: Long = 0L
    private var lastBidOrderId: Long? = null
    private var lastAskOrderId: Long? = null
    private var lastGateReason: String? = null
    private var lastGateMs: Long = 0L
    private var adaptiveMinSpreadPct: Double = config.minSpreadPct
    private var lastAdaptiveUpdateMs: Long = 0L
    private val gate = RegimeGate(config)
    private val quoter = QuoteCalculator(config)

    suspend fun onMarketState(state: MarketState) {
        if (state.symbol != config.symbol) return
        val now = state.eventTimeMs ?: state.timestampMs
        if (now - lastActionMs < config.quoteRefreshMs) return

        val mid = state.midPrice ?: state.microPrice ?: return
        val spread = state.spread ?: return
        if (spread <= 0.0) return
        val spreadPct = spread / mid
        gate.addSpreadSample(now, spreadPct)
        val gateReason = gate.check(state, spreadPct, now)
        Telemetry.emit(
            type = "strategy_signal",
            tsMs = now,
            data = mapOf(
                "strategy_id" to "avellaneda_mm",
                "symbol" to config.symbol,
                "spread_pct" to spreadPct,
                "mid" to mid,
                "vol_1s" to state.vol1s,
                "depth_imbalance" to state.depthImbalance,
                "gate_reason" to gateReason
            )
        )
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
        updateAdaptiveSpread(now)
        val quote = quoter.compute(state, positionQty, adaptiveMinSpreadPct) ?: return
        val bid = quote.bid
        val ask = quote.ask
        val bestBid = state.bestBidPrice
        val bestAsk = state.bestAskPrice

        val allowBid = positionQty < config.maxInventory && (bestAsk == null || bid < bestAsk)
        val allowAsk = positionQty > -config.maxInventory && (bestBid == null || ask > bestBid)

        if (allowBid) {
            Telemetry.emit(
                type = "strategy_intent",
                tsMs = now,
                data = mapOf(
                    "strategy_id" to "avellaneda_mm",
                    "symbol" to config.symbol,
                    "desired_delta" to config.orderQty,
                    "urgency" to "LOW",
                    "prefer_maker" to true,
                    "ttl_ms" to config.maxQuoteAgeMs,
                    "limit_price" to bid,
                    "reason" to "quote_bid"
                )
            )
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
            Telemetry.emit(
                type = "strategy_intent",
                tsMs = now,
                data = mapOf(
                    "strategy_id" to "avellaneda_mm",
                    "symbol" to config.symbol,
                    "desired_delta" to -config.orderQty,
                    "urgency" to "LOW",
                    "prefer_maker" to true,
                    "ttl_ms" to config.maxQuoteAgeMs,
                    "limit_price" to ask,
                    "reason" to "quote_ask"
                )
            )
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

    private fun updateAdaptiveSpread(nowMs: Long) {
        val targetBps = config.adaptiveSpreadTargetBps ?: return
        if (nowMs - lastAdaptiveUpdateMs < config.adaptiveSpreadUpdateMs) return
        lastAdaptiveUpdateMs = nowMs

        val advBpsRaw = adverseBpsProvider?.invoke(config.symbol) ?: 0.0
        val toxicityBps = max(0.0, advBpsRaw)
        val feeBps = config.makerFeePct * 10_000.0
        val requiredBps = (2.0 * feeBps) + targetBps + (2.0 * toxicityBps)
        adaptiveMinSpreadPct = (requiredBps / 10_000.0).coerceAtLeast(config.minSpreadPct)
        if (config.logGateDecisions) {
            println(
                "adaptiveSpread=${config.symbol} minSpreadPct=${"%.6f".format(adaptiveMinSpreadPct)} " +
                    "feeBps=${"%.2f".format(feeBps)} advBps=${"%.2f".format(advBpsRaw)}"
            )
        }
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
