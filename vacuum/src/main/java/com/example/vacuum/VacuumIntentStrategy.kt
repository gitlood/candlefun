package com.example.vacuum

import com.example.account.domain.Qty
import com.example.account.domain.Symbol
import com.example.execution.domain.IntentStrategy
import com.example.execution.domain.IntentUrgency
import com.example.execution.domain.StrategyContext
import com.example.execution.domain.StrategyIntent
import com.example.platform.model.MarketState
import com.example.platform.model.enums.OrderSide
import com.example.platform.report.Telemetry
import com.example.vacuum.util.RollingAverageWindow
import com.example.vacuum.util.RollingMaxWindow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class VacuumIntentStrategy(
    override val id: String = "vacuum",
    private val config: VacuumConfig,
    private val kpi: VacuumKpiTracker? = null
) : IntentStrategy {
    private val depthWindow = RollingMaxWindow(config.depthWindowMs)
    private val spreadWindow = RollingAverageWindow(config.spreadWindowMs)
    private var lastActionMs = 0L
    private var positionSide: OrderSide? = null
    private var positionQty: Double = 0.0
    private var entryTimeMs: Long? = null
    private var entryMid: Double? = null
    private var peakFavorableMid: Double? = null
    private var pausedUntilMs: Long = 0L

    override fun onMarketState(state: MarketState, context: StrategyContext): List<StrategyIntent> {
        if (state.symbol != config.symbol) return emptyList()
        val now = state.eventTimeMs ?: state.timestampMs
        syncPosition(context)

        val signal = buildSignal(state, now) ?: return emptyList()
        Telemetry.emit(
            type = "strategy_signal",
            tsMs = now,
            data = mapOf(
                "strategy_id" to id,
                "symbol" to config.symbol,
                "depth_drop_pct" to signal.depthDropPct,
                "spread_pct" to signal.spreadPct,
                "trade_count_1s" to signal.tradeCount,
                "trade_imbalance_1s" to signal.tradeImbalance
            )
        )
        if (now < pausedUntilMs) return emptyList()

        val intents = ArrayList<StrategyIntent>(1)
        if (positionSide == null) {
            if (now - lastActionMs < config.entryCooldownMs) return emptyList()
            if (shouldEnter(signal)) {
                val side = if (signal.tradeImbalance > 0.0) OrderSide.BUY else OrderSide.SELL
                intents.add(enterIntent(side, now))
            }
        } else {
            updateTrailing(state)
            if (shouldExit(state, signal, now)) {
                intents.add(exitIntent(now))
            }
        }

        if (shouldPause(now)) {
            pausedUntilMs = now + config.pauseMs
        }
        return intents
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

    private fun enterIntent(side: OrderSide, now: Long): StrategyIntent {
        val qty = config.orderQty
        val signedQty = if (side == OrderSide.BUY) qty else -qty
        entryTimeMs = now
        entryMid = null
        peakFavorableMid = null
        lastActionMs = now
        Telemetry.emit(
            type = "strategy_intent",
            tsMs = now,
            data = mapOf(
                "strategy_id" to id,
                "symbol" to config.symbol,
                "desired_delta" to signedQty,
                "urgency" to "HIGH",
                "prefer_maker" to false,
                "ttl_ms" to config.orderTtlMs,
                "reason" to "vacuum_entry"
            )
        )
        return StrategyIntent(
            strategyId = id,
            symbol = Symbol.of(config.symbol),
            desiredDelta = Qty.fromDouble(signedQty),
            urgency = IntentUrgency.HIGH,
            preferMaker = false,
            ttlMs = config.orderTtlMs,
            confidence = 1.0,
            reason = "vacuum_entry"
        )
    }

    private fun exitIntent(now: Long): StrategyIntent {
        val side = positionSide ?: return StrategyIntent(
            strategyId = id,
            symbol = Symbol.of(config.symbol),
            desiredDelta = Qty.ZERO,
            urgency = IntentUrgency.HIGH,
            preferMaker = false,
            ttlMs = config.orderTtlMs,
            confidence = 0.0,
            reason = "vacuum_exit_no_position"
        )
        val qty = abs(positionQty).takeIf { it > 0.0 } ?: config.orderQty
        val signedQty = if (side == OrderSide.BUY) -qty else qty
        entryTimeMs = null
        entryMid = null
        peakFavorableMid = null
        lastActionMs = now
        Telemetry.emit(
            type = "strategy_intent",
            tsMs = now,
            data = mapOf(
                "strategy_id" to id,
                "symbol" to config.symbol,
                "desired_delta" to signedQty,
                "urgency" to "HIGH",
                "prefer_maker" to false,
                "ttl_ms" to config.orderTtlMs,
                "reason" to "vacuum_exit"
            )
        )
        return StrategyIntent(
            strategyId = id,
            symbol = Symbol.of(config.symbol),
            desiredDelta = Qty.fromDouble(signedQty),
            urgency = IntentUrgency.HIGH,
            preferMaker = false,
            ttlMs = config.orderTtlMs,
            confidence = 1.0,
            reason = "vacuum_exit"
        )
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

    private fun depthNotional(bids: List<com.example.platform.model.BookLevel>, asks: List<com.example.platform.model.BookLevel>): Double {
        val topBids = bids.take(config.depthLevels)
        val topAsks = asks.take(config.depthLevels)
        var sum = 0.0
        for (lvl in topBids) sum += lvl.price * lvl.quantity
        for (lvl in topAsks) sum += lvl.price * lvl.quantity
        return sum
    }

    private fun shouldPause(now: Long): Boolean {
        val tracker = kpi ?: return false
        val slip = tracker.lastSlippageBps() ?: return false
        if (slip > config.slippagePauseBps) return true
        if (tracker.tailLossCount() >= config.maxTailLosses) return true
        return false
    }

    private fun syncPosition(context: StrategyContext) {
        val qty = context.positionQty(Symbol.of(config.symbol))
        positionQty = qty
        positionSide = when {
            qty > 0.0 -> OrderSide.BUY
            qty < 0.0 -> OrderSide.SELL
            else -> null
        }
        if (positionSide == null) {
            entryTimeMs = null
            peakFavorableMid = null
        }
    }
}
