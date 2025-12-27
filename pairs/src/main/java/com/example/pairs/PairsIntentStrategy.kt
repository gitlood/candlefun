package com.example.pairs

import com.example.account.domain.Qty
import com.example.account.domain.Symbol
import com.example.execution.domain.IntentStrategy
import com.example.execution.domain.IntentUrgency
import com.example.execution.domain.StrategyContext
import com.example.execution.domain.StrategyIntent
import com.example.platform.model.MarketState
import com.example.platform.model.enums.OrderSide
import com.example.platform.report.Telemetry
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max

class PairsIntentStrategy(
    override val id: String = "pairs",
    private val config: PairsConfig
) : IntentStrategy {
    private val stats = PairsStatsWindow(config.windowMs)
    private val spreadWindow = SpreadWindow(config.windowMs)
    private var lastSignalMs = 0L
    private var side = PairSide.FLAT
    private var entryTimeMs: Long? = null
    private var trendCount = 0
    private var lastZ: Double? = null
    private var lastSign: Int = 0

    private var lastStateA: MarketState? = null
    private var lastStateB: MarketState? = null

    override fun onMarketState(state: MarketState, context: StrategyContext): List<StrategyIntent> {
        if (state.symbol == config.symbolA) {
            lastStateA = state
        } else if (state.symbol == config.symbolB) {
            lastStateB = state
        } else {
            return emptyList()
        }

        val a = lastStateA ?: return emptyList()
        val b = lastStateB ?: return emptyList()
        val now = maxOf(
            a.eventTimeMs ?: a.timestampMs,
            b.eventTimeMs ?: b.timestampMs
        )
        val signal = buildSignal(a, b, now) ?: return emptyList()

        val ok = regimeOk(a, b, signal)
        Telemetry.emit(
            type = "strategy_signal",
            tsMs = now,
            data = mapOf(
                "strategy_id" to id,
                "symbol_a" to config.symbolA,
                "symbol_b" to config.symbolB,
                "z_score" to signal.zScore,
                "beta" to signal.beta,
                "corr" to signal.corr,
                "spread" to signal.spread,
                "regime_ok" to ok,
                "trend_count" to trendCount
            )
        )
        if (!ok) return emptyList()

        if (side == PairSide.FLAT) {
            if (now - lastSignalMs < 100L) return emptyList()
            if (abs(signal.zScore) >= config.entryZ) {
                val nextSide =
                    if (signal.zScore > 0.0) PairSide.SHORT_A_LONG_B else PairSide.LONG_A_SHORT_B
                lastSignalMs = now
                return enter(nextSide, a, b, signal)
            }
        } else {
            if (shouldExit(a, b, signal, now)) {
                return exit(a, b, signal)
            }
        }

        return emptyList()
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

    private fun enter(side: PairSide, a: MarketState, b: MarketState, signal: PairsSignal): List<StrategyIntent> {
        val (sideA, sideB) = if (side == PairSide.LONG_A_SHORT_B) {
            OrderSide.BUY to OrderSide.SELL
        } else {
            OrderSide.SELL to OrderSide.BUY
        }
        val priceA = priceForSide(a, sideA) ?: return emptyList()
        val priceB = priceForSide(b, sideB) ?: return emptyList()
        val qtyA = notionalQty(priceA, config.qtyStepA, config.minQtyA, config.minNotionalA)
        val qtyB = notionalQty(priceB, config.qtyStepB, config.minQtyB, config.minNotionalB)
        if (qtyA <= 0.0 || qtyB <= 0.0) return emptyList()

        this.side = side
        entryTimeMs = signal.timestampMs
        val intents = listOf(
            StrategyIntent(
                strategyId = id,
                symbol = Symbol.of(config.symbolA),
                desiredDelta = Qty.fromDouble(if (sideA == OrderSide.BUY) qtyA else -qtyA),
                urgency = IntentUrgency.LOW,
                preferMaker = true,
                ttlMs = config.orderTtlMs,
                confidence = 1.0,
                reason = "pairs_entry"
            ),
            StrategyIntent(
                strategyId = id,
                symbol = Symbol.of(config.symbolB),
                desiredDelta = Qty.fromDouble(if (sideB == OrderSide.BUY) qtyB else -qtyB),
                urgency = IntentUrgency.LOW,
                preferMaker = true,
                ttlMs = config.orderTtlMs,
                confidence = 1.0,
                reason = "pairs_entry"
            )
        )
        Telemetry.emit(
            type = "strategy_intent",
            tsMs = signal.timestampMs,
            data = mapOf(
                "strategy_id" to id,
                "symbol" to config.symbolA,
                "desired_delta" to if (sideA == OrderSide.BUY) qtyA else -qtyA,
                "urgency" to "LOW",
                "prefer_maker" to true,
                "ttl_ms" to config.orderTtlMs,
                "reason" to "pairs_entry"
            )
        )
        Telemetry.emit(
            type = "strategy_intent",
            tsMs = signal.timestampMs,
            data = mapOf(
                "strategy_id" to id,
                "symbol" to config.symbolB,
                "desired_delta" to if (sideB == OrderSide.BUY) qtyB else -qtyB,
                "urgency" to "LOW",
                "prefer_maker" to true,
                "ttl_ms" to config.orderTtlMs,
                "reason" to "pairs_entry"
            )
        )
        return intents
    }

    private fun exit(a: MarketState, b: MarketState, signal: PairsSignal): List<StrategyIntent> {
        val (sideA, sideB) = if (side == PairSide.LONG_A_SHORT_B) {
            OrderSide.SELL to OrderSide.BUY
        } else {
            OrderSide.BUY to OrderSide.SELL
        }
        val priceA = priceForSide(a, sideA) ?: return emptyList()
        val priceB = priceForSide(b, sideB) ?: return emptyList()
        val qtyA = notionalQty(priceA, config.qtyStepA, config.minQtyA, config.minNotionalA)
        val qtyB = notionalQty(priceB, config.qtyStepB, config.minQtyB, config.minNotionalB)
        if (qtyA <= 0.0 || qtyB <= 0.0) return emptyList()

        this.side = PairSide.FLAT
        entryTimeMs = null
        val intents = listOf(
            StrategyIntent(
                strategyId = id,
                symbol = Symbol.of(config.symbolA),
                desiredDelta = Qty.fromDouble(if (sideA == OrderSide.BUY) qtyA else -qtyA),
                urgency = IntentUrgency.HIGH,
                preferMaker = true,
                ttlMs = config.orderTtlMs,
                confidence = 1.0,
                reason = "pairs_exit"
            ),
            StrategyIntent(
                strategyId = id,
                symbol = Symbol.of(config.symbolB),
                desiredDelta = Qty.fromDouble(if (sideB == OrderSide.BUY) qtyB else -qtyB),
                urgency = IntentUrgency.HIGH,
                preferMaker = true,
                ttlMs = config.orderTtlMs,
                confidence = 1.0,
                reason = "pairs_exit"
            )
        )
        Telemetry.emit(
            type = "strategy_intent",
            tsMs = signal.timestampMs,
            data = mapOf(
                "strategy_id" to id,
                "symbol" to config.symbolA,
                "desired_delta" to if (sideA == OrderSide.BUY) qtyA else -qtyA,
                "urgency" to "HIGH",
                "prefer_maker" to true,
                "ttl_ms" to config.orderTtlMs,
                "reason" to "pairs_exit"
            )
        )
        Telemetry.emit(
            type = "strategy_intent",
            tsMs = signal.timestampMs,
            data = mapOf(
                "strategy_id" to id,
                "symbol" to config.symbolB,
                "desired_delta" to if (sideB == OrderSide.BUY) qtyB else -qtyB,
                "urgency" to "HIGH",
                "prefer_maker" to true,
                "ttl_ms" to config.orderTtlMs,
                "reason" to "pairs_exit"
            )
        )
        return intents
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

    private fun roundDown(value: Double, step: Double): Double {
        if (step <= 0.0) return value
        return kotlin.math.floor(value / step) * step
    }
}
