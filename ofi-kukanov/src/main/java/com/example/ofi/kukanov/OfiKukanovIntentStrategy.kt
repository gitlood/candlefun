package com.example.ofi.kukanov

import com.example.account.domain.Qty
import com.example.account.domain.Symbol
import com.example.execution.domain.IntentStrategy
import com.example.execution.domain.StrategyContext
import com.example.execution.domain.StrategyIntent
import com.example.execution.domain.IntentUrgency
import com.example.ofi.kukanov.util.RollingAverageWindow
import com.example.platform.model.MarketState
import com.example.platform.model.enums.OrderSide
import com.example.platform.report.Telemetry
import kotlin.math.abs

class OfiKukanovIntentStrategy(
    override val id: String = "ofi_kukanov",
    private val config: OfiStrategyConfig,
    signalConfig: KukanovOfiConfig = KukanovOfiConfig()
) : IntentStrategy {
    private val accumulator = KukanovOfiAccumulator(signalConfig)
    private val spreadWindow = RollingAverageWindow(config.spreadWindowMs)
    private var lastActionMs = 0L
    private var entryTimeMs: Long? = null
    private var lastPositionQty = 0.0

    override fun onMarketState(state: MarketState, context: StrategyContext): List<StrategyIntent> {
        if (state.symbol != config.symbol) return emptyList()
        val signal = accumulator.update(state)
        val now = signal.eventTimeMs ?: signal.timestampMs
        val spreadPct = spreadPct(signal)
        if (spreadPct != null) spreadWindow.add(now, spreadPct)

        val normalized = signal.normalizedOfi ?: return emptyList()
        val posQty = context.positionQty(Symbol.of(config.symbol))
        updateEntryTime(posQty, now)

        val spreadOk = isSpreadStable(spreadPct, now)
        val depthOk = isDepthHealthy(signal)
        val tradeOk = isTradeConfirmed(state, normalized)
        Telemetry.emit(
            type = "strategy_signal",
            tsMs = now,
            data = mapOf(
                "strategy_id" to id,
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

        if (now - lastActionMs < config.minSignalIntervalMs) return emptyList()

        if (posQty == 0.0) {
            if (!spreadOk || !depthOk || !tradeOk) return emptyList()
            if (abs(normalized) >= config.entryThreshold) {
                val side = if (normalized > 0.0) OrderSide.BUY else OrderSide.SELL
                val style = resolveEntryStyle(normalized)
                val confidence = entryConfidence(normalized)
                return listOf(entryIntent(side, style, confidence, now))
            }
        } else if (shouldExit(posQty, normalized, now)) {
            return listOf(exitIntent(posQty, now))
        }

        return emptyList()
    }

    private fun entryIntent(
        side: OrderSide,
        style: OfiOrderStyle,
        confidence: Double,
        nowMs: Long
    ): StrategyIntent {
        val signedQty = if (side == OrderSide.BUY) config.orderQty else -config.orderQty
        Telemetry.emit(
            type = "strategy_intent",
            tsMs = nowMs,
            data = mapOf(
                "strategy_id" to id,
                "symbol" to config.symbol,
                "desired_delta" to signedQty,
                "urgency" to "MEDIUM",
                "prefer_maker" to (style == OfiOrderStyle.JOIN),
                "ttl_ms" to if (style == OfiOrderStyle.TAKE) config.takeOrderTtlMs else config.orderTtlMs,
                "reason" to "ofi_entry",
                "confidence" to confidence
            )
        )
        lastActionMs = nowMs
        return StrategyIntent(
            strategyId = id,
            symbol = Symbol.of(config.symbol),
            desiredDelta = Qty.fromDouble(signedQty),
            urgency = IntentUrgency.MEDIUM,
            preferMaker = (style == OfiOrderStyle.JOIN),
            ttlMs = if (style == OfiOrderStyle.TAKE) config.takeOrderTtlMs else config.orderTtlMs,
            confidence = confidence,
            riskBudgetRequest = abs(config.orderQty),
            reason = "ofi_entry"
        )
    }

    private fun exitIntent(posQty: Double, nowMs: Long): StrategyIntent {
        val signedQty = if (posQty > 0.0) -abs(posQty) else abs(posQty)
        Telemetry.emit(
            type = "strategy_intent",
            tsMs = nowMs,
            data = mapOf(
                "strategy_id" to id,
                "symbol" to config.symbol,
                "desired_delta" to signedQty,
                "urgency" to "HIGH",
                "prefer_maker" to (config.orderStyle == OfiOrderStyle.JOIN),
                "ttl_ms" to if (config.orderStyle == OfiOrderStyle.TAKE) config.takeOrderTtlMs else config.orderTtlMs,
                "reason" to "ofi_exit"
            )
        )
        lastActionMs = nowMs
        return StrategyIntent(
            strategyId = id,
            symbol = Symbol.of(config.symbol),
            desiredDelta = Qty.fromDouble(signedQty),
            urgency = IntentUrgency.HIGH,
            preferMaker = (config.orderStyle == OfiOrderStyle.JOIN),
            ttlMs = if (config.orderStyle == OfiOrderStyle.TAKE) config.takeOrderTtlMs else config.orderTtlMs,
            confidence = 1.0,
            riskBudgetRequest = abs(signedQty),
            reason = "ofi_exit"
        )
    }

    private fun shouldExit(posQty: Double, normalized: Double, nowMs: Long): Boolean {
        val entryMs = entryTimeMs ?: return false
        if (nowMs - entryMs >= config.maxHoldMs) return true
        if (abs(normalized) <= config.exitThreshold) return true
        val posDir = direction(posQty)
        val ofiDir = direction(normalized)
        return ofiDir != 0 && ofiDir != posDir
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

    private fun resolveEntryStyle(normalized: Double): OfiOrderStyle {
        val edge = abs(normalized) * config.entryEdgeMultiplier
        return if (edge >= config.takeMinEdgeBps / 10_000.0) {
            OfiOrderStyle.TAKE
        } else {
            config.orderStyle
        }
    }

    private fun entryConfidence(normalized: Double): Double {
        val threshold = config.entryThreshold
        if (threshold <= 0.0) return 0.5
        val strength = abs(normalized) / threshold
        return strength.coerceIn(0.0, 1.0)
    }

    private fun direction(value: Double): Int {
        return when {
            value > 0.0 -> 1
            value < 0.0 -> -1
            else -> 0
        }
    }
}
