package com.example.pairs

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
import kotlin.math.ln
import kotlin.math.max

class PairsStrategy(
    private val gateway: ExecutionGateway,
    private val config: PairsConfig,
    private val kpi: PairsKpiTracker
) {
    private val stats = PairsStatsWindow(config.windowMs)
    private val spreadWindow = SpreadWindow(config.windowMs)
    private var lastSignalMs = 0L
    private var side = PairSide.FLAT
    private var entryTimeMs: Long? = null
    private var trendCount = 0
    private var lastZ: Double? = null
    private var lastSign: Int = 0
    private var activeOrderIdA: Long? = null
    private var activeOrderIdB: Long? = null
    private var activeOrderMs: Long? = null

    private var lastStateA: MarketState? = null
    private var lastStateB: MarketState? = null

    suspend fun onMarketState(state: MarketState) {
        if (state.symbol == config.symbolA) {
            lastStateA = state
        } else if (state.symbol == config.symbolB) {
            lastStateB = state
        } else {
            return
        }

        val a = lastStateA ?: return
        val b = lastStateB ?: return
        val now = maxOf(
            a.eventTimeMs ?: a.timestampMs,
            b.eventTimeMs ?: b.timestampMs
        )
        cancelStaleOrders(now)
        val signal = buildSignal(a, b, now) ?: return

        if (!regimeOk(a, b, signal)) return

        if (side == PairSide.FLAT) {
            if (now - lastSignalMs < 100L) return
            if (abs(signal.zScore) >= config.entryZ) {
                val nextSide =
                    if (signal.zScore > 0.0) PairSide.SHORT_A_LONG_B else PairSide.LONG_A_SHORT_B
                enter(nextSide, a, b, signal)
                lastSignalMs = now
            }
        } else {
            if (shouldExit(a, b, signal, now)) {
                exit(a, b, signal)
            }
        }
    }

    private fun buildSignal(a: MarketState, b: MarketState, now: Long): PairsSignal? {
        val priceA = a.midPrice ?: a.microPrice ?: return null
        val priceB = b.midPrice ?: b.microPrice ?: return null
        if (priceA <= 0.0 || priceB <= 0.0) return null

        val logA = ln(priceA)
        val logB = ln(priceB)
        stats.add(now, logA, logB)
        if (stats.size(now) < config.minSamples) return null

        val beta = stats.beta(now)
        val spread = logA - beta * logB
        spreadWindow.add(now, spread)
        val mean = spreadWindow.mean(now) ?: return null
        val std = spreadWindow.std(now) ?: return null
        if (std <= 0.0) return null
        val z = (spread - mean) / std
        val corr = stats.correlation(now)
        updateTrend(z)

        kpi.onTailEvent(z)

        return PairsSignal(
            timestampMs = now,
            spread = spread,
            beta = beta,
            mean = mean,
            std = std,
            zScore = z,
            corr = corr
        )
    }

    private fun regimeOk(a: MarketState, b: MarketState, signal: PairsSignal): Boolean {
        val volA = a.vol1s ?: a.vol5s
        val volB = b.vol1s ?: b.vol5s
        if (volA != null && volA > config.maxVol) return false
        if (volB != null && volB > config.maxVol) return false
        if (abs(signal.corr) < config.minCorr) return false
        if (trendCount >= config.trendCountLimit && config.trendCountLimit > 0) return false
        return true
    }

    private fun shouldExit(a: MarketState, b: MarketState, signal: PairsSignal, now: Long): Boolean {
        if (abs(signal.zScore) <= config.exitZ) return true
        val entry = entryTimeMs ?: return false
        if (now - entry >= config.maxHoldMs) return true
        if (abs(signal.corr) < config.minCorr) return true
        val volA = a.vol1s ?: a.vol5s
        val volB = b.vol1s ?: b.vol5s
        if (volA != null && volA > config.maxVol) return true
        if (volB != null && volB > config.maxVol) return true
        if (trendCount >= config.trendCountLimit && config.trendCountLimit > 0) return true
        return false
    }

    private suspend fun enter(side: PairSide, a: MarketState, b: MarketState, signal: PairsSignal) {
        cancelOpenOrders()
        val (sideA, sideB) = if (side == PairSide.LONG_A_SHORT_B) {
            OrderSide.BUY to OrderSide.SELL
        } else {
            OrderSide.SELL to OrderSide.BUY
        }
        val priceA = priceForSide(a, sideA)?.let { roundPrice(it, sideA, config.priceTickA) } ?: return
        val priceB = priceForSide(b, sideB)?.let { roundPrice(it, sideB, config.priceTickB) } ?: return
        val qtyA = notionalQty(priceA, config.qtyStepA, config.minQtyA, config.minNotionalA)
        val qtyB = notionalQty(priceB, config.qtyStepB, config.minQtyB, config.minNotionalB)
        if (qtyA <= 0.0 || qtyB <= 0.0) return

        val orderA = gateway.placeOrder(
            OrderRequest(
                symbol = Symbol.of(config.symbolA),
                side = sideA,
                type = OrderType.LIMIT,
                quantity = Qty.fromString(formatToStep(qtyA, config.qtyStepA)),
                price = Price.fromString(formatToStep(priceA, config.priceTickA)),
                timeInForce = TimeInForce.GTC,
                clientOrderId = "pairs_${config.symbolA}_${sideA.name}_${signal.timestampMs}"
            )
        )
        val orderB = gateway.placeOrder(
            OrderRequest(
                symbol = Symbol.of(config.symbolB),
                side = sideB,
                type = OrderType.LIMIT,
                quantity = Qty.fromString(formatToStep(qtyB, config.qtyStepB)),
                price = Price.fromString(formatToStep(priceB, config.priceTickB)),
                timeInForce = TimeInForce.GTC,
                clientOrderId = "pairs_${config.symbolB}_${sideB.name}_${signal.timestampMs}"
            )
        )

        activeOrderIdA = orderA.orderId
        activeOrderIdB = orderB.orderId
        activeOrderMs = signal.timestampMs
        this.side = side
        entryTimeMs = signal.timestampMs
        kpi.onOpenSide(side)
        kpi.onEntry(signal.timestampMs, signal.spread, signal.zScore)
        kpi.onFees(config.notional * 2.0, taker = true)
        if (config.logSignals) {
            println("pairs entry side=$side z=${"%.2f".format(signal.zScore)} beta=${"%.3f".format(signal.beta)}")
        }
    }

    private suspend fun exit(a: MarketState, b: MarketState, signal: PairsSignal) {
        cancelOpenOrders()
        val (sideA, sideB) = if (side == PairSide.LONG_A_SHORT_B) {
            OrderSide.SELL to OrderSide.BUY
        } else {
            OrderSide.BUY to OrderSide.SELL
        }
        val priceA = priceForSide(a, sideA)?.let { roundPrice(it, sideA, config.priceTickA) } ?: return
        val priceB = priceForSide(b, sideB)?.let { roundPrice(it, sideB, config.priceTickB) } ?: return
        val qtyA = notionalQty(priceA, config.qtyStepA, config.minQtyA, config.minNotionalA)
        val qtyB = notionalQty(priceB, config.qtyStepB, config.minQtyB, config.minNotionalB)
        if (qtyA <= 0.0 || qtyB <= 0.0) return

        gateway.placeOrder(
            OrderRequest(
                symbol = Symbol.of(config.symbolA),
                side = sideA,
                type = OrderType.LIMIT,
                quantity = Qty.fromString(formatToStep(qtyA, config.qtyStepA)),
                price = Price.fromString(formatToStep(priceA, config.priceTickA)),
                timeInForce = TimeInForce.GTC,
                clientOrderId = "pairs_exit_${config.symbolA}_${sideA.name}_${signal.timestampMs}"
            )
        )
        gateway.placeOrder(
            OrderRequest(
                symbol = Symbol.of(config.symbolB),
                side = sideB,
                type = OrderType.LIMIT,
                quantity = Qty.fromString(formatToStep(qtyB, config.qtyStepB)),
                price = Price.fromString(formatToStep(priceB, config.priceTickB)),
                timeInForce = TimeInForce.GTC,
                clientOrderId = "pairs_exit_${config.symbolB}_${sideB.name}_${signal.timestampMs}"
            )
        )

        kpi.onExit(signal.timestampMs, signal.spread, config.notional)
        kpi.onFees(config.notional * 2.0, taker = true)
        kpi.onOpenSide(PairSide.FLAT)
        side = PairSide.FLAT
        entryTimeMs = null
        if (config.logSignals) {
            println("pairs exit z=${"%.2f".format(signal.zScore)}")
        }
    }

    private fun priceForSide(state: MarketState, side: OrderSide): Double? {
        return when (side) {
            OrderSide.BUY -> state.bestAskPrice ?: state.midPrice ?: state.microPrice
            OrderSide.SELL -> state.bestBidPrice ?: state.midPrice ?: state.microPrice
        }
    }

    private fun notionalQty(
        price: Double,
        step: Double,
        minQty: Double?,
        minNotional: Double?
    ): Double {
        var qty = config.notional / price
        if (minNotional != null && minNotional > 0.0) {
            val minByNotional = minNotional / price
            if (qty < minByNotional) qty = minByNotional
        }
        if (minQty != null && minQty > 0.0 && qty < minQty) qty = minQty
        val rounded = roundDown(qty, step)
        if (minQty != null && rounded < minQty) return 0.0
        if (minNotional != null && rounded * price < minNotional) return 0.0
        return rounded
    }

    private fun updateTrend(z: Double) {
        val sign = if (z > 0) 1 else if (z < 0) -1 else 0
        val prevZ = lastZ
        val prevSign = lastSign
        lastZ = z
        lastSign = sign
        if (sign == 0 || prevZ == null) {
            trendCount = 0
            return
        }
        val increasing = abs(z) > abs(prevZ)
        trendCount = if (sign == prevSign && increasing) trendCount + 1 else 0
    }

    private suspend fun cancelOpenOrders() {
        val openA = gateway.getOpenOrders(Symbol.of(config.symbolA))
        val openB = gateway.getOpenOrders(Symbol.of(config.symbolB))
        for (order in openA + openB) {
            gateway.cancelOrder(OrderCancelRequest(symbol = order.symbol, orderId = order.orderId))
        }
        activeOrderIdA = null
        activeOrderIdB = null
        activeOrderMs = null
    }

    private suspend fun cancelStaleOrders(nowMs: Long) {
        val placed = activeOrderMs ?: return
        if (config.orderTtlMs <= 0L) return
        if (nowMs - placed < config.orderTtlMs) return
        cancelOpenOrders()
    }

    private fun roundDown(value: Double, step: Double): Double {
        if (step <= 0.0) return value
        return kotlin.math.floor(value / step) * step
    }

    private fun roundPrice(price: Double, side: OrderSide, tick: Double): Double {
        if (tick <= 0.0) return price
        val units = price / tick
        return if (side == OrderSide.BUY) {
            kotlin.math.ceil(units) * tick
        } else {
            kotlin.math.floor(units) * tick
        }
    }

    private fun formatToStep(value: Double, step: Double): String {
        if (step <= 0.0) return java.math.BigDecimal.valueOf(value).toPlainString()
        val stepBd = java.math.BigDecimal.valueOf(step).stripTrailingZeros()
        val scale = stepBd.scale().coerceAtLeast(0)
        val units = java.math.BigDecimal.valueOf(value).divide(stepBd, 0, java.math.RoundingMode.DOWN)
        val rounded = units.multiply(stepBd).setScale(scale, java.math.RoundingMode.DOWN)
        return rounded.stripTrailingZeros().toPlainString()
    }
}
