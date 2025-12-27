package com.example.execution.impl

import com.example.account.domain.Qty
import com.example.account.domain.Symbol
import com.example.execution.domain.ExecutionGateway
import com.example.execution.domain.IntentStrategy
import com.example.execution.domain.StrategyContext
import com.example.execution.domain.StrategyRegimeState
import com.example.execution.domain.StrategyIntent
import com.example.platform.model.MarketState

class PortfolioEngine(
    private val gateway: ExecutionGateway,
    private val allocator: IntentAllocator,
    private val policy: ExecutionPolicy,
    private val strategies: List<IntentStrategy>,
    private val positionRefreshMs: Long = 1_000L,
    private val regimeEngine: RegimeEngine = RegimeEngine.fromEnv(),
    private val maxTotalNotionalUsd: Double = envDouble("MAX_TOTAL_NOTIONAL_USD", 0.0),
    private val maxSymbolNotionalUsd: Double = envDouble("MAX_SYMBOL_NOTIONAL_USD", 0.0),
    private val maxLatencyMs: Long = envLong("MAX_LATENCY_MS", 0L)
) {
    private val latestStates = mutableMapOf<String, MarketState>()
    private var lastPositionRefreshMs: Long = 0L
    private var positions = emptyMap<Symbol, Qty>()

    suspend fun onMarketState(state: MarketState) {
        latestStates[state.symbol] = state
        val now = state.eventTimeMs ?: state.timestampMs
        if (maxLatencyMs > 0L) {
            val wallNow = System.currentTimeMillis()
            val eventTime = state.eventTimeMs ?: wallNow
            val latency = wallNow - eventTime
            if (latency > maxLatencyMs) {
                return
            }
        }
        refreshPositions(now)

        val context = StrategyContext(positions = positions, nowMs = now)
        val intents = strategies.flatMap { it.onMarketState(state, context) }
        val regimes = regimeEngine.evaluate(state)
        route(intents, now, regimes)
        policy.onMarketState(state, now)
    }

    private suspend fun route(
        intents: List<StrategyIntent>,
        nowMs: Long,
        regimes: Map<String, StrategyRegimeState>
    ) {
        if (intents.isEmpty()) return
        val (summaries, decisions) = allocator.allocate(intents, regimes)
        if (decisions.isEmpty()) return
        for (decision in decisions) {
            val symbolState = latestStates[decision.symbol.value] ?: continue
            val scaled = applyRiskCaps(decision, symbolState) ?: continue
            policy.route(scaled, symbolState, nowMs)
        }
    }

    private suspend fun refreshPositions(nowMs: Long) {
        if (nowMs - lastPositionRefreshMs < positionRefreshMs) return
        val next = gateway.getPositions().associate { it.symbol to it.quantity }
        positions = next
        lastPositionRefreshMs = nowMs
    }

    private fun applyRiskCaps(decision: com.example.execution.domain.RoutingDecision, state: MarketState)
        : com.example.execution.domain.RoutingDecision? {
        val mid = state.midPrice ?: state.microPrice ?: return decision
        if (mid <= 0.0) return decision
        val absDelta = decision.netDelta.value.abs().toDouble()
        val deltaNotional = absDelta * mid
        if (deltaNotional <= 0.0) return null

        var scale = 1.0
        val totalExposure = totalExposureUsd()
        if (maxTotalNotionalUsd > 0.0 && totalExposure + deltaNotional > maxTotalNotionalUsd) {
            val remaining = maxTotalNotionalUsd - totalExposure
            if (remaining <= 0.0) return null
            scale = minOf(scale, remaining / deltaNotional)
        }
        val symbolExposure = symbolExposureUsd(decision.symbol)
        if (maxSymbolNotionalUsd > 0.0 && symbolExposure + deltaNotional > maxSymbolNotionalUsd) {
            val remaining = maxSymbolNotionalUsd - symbolExposure
            if (remaining <= 0.0) return null
            scale = minOf(scale, remaining / deltaNotional)
        }
        if (scale >= 0.999) return decision
        val scaledDelta = Qty(decision.netDelta.value.multiply(java.math.BigDecimal.valueOf(scale)))
        return decision.copy(netDelta = scaledDelta)
    }

    private fun totalExposureUsd(): Double {
        var sum = 0.0
        for ((symbol, qty) in positions) {
            val state = latestStates[symbol.value] ?: continue
            val mid = state.midPrice ?: state.microPrice ?: continue
            sum += kotlin.math.abs(qty.toDouble()) * mid
        }
        return sum
    }

    private fun symbolExposureUsd(symbol: Symbol): Double {
        val qty = positions[symbol]?.toDouble() ?: 0.0
        val state = latestStates[symbol.value] ?: return 0.0
        val mid = state.midPrice ?: state.microPrice ?: return 0.0
        return kotlin.math.abs(qty) * mid
    }

    private companion object {
        fun envDouble(name: String, default: Double): Double {
            return System.getenv(name)?.toDoubleOrNull() ?: default
        }

        fun envLong(name: String, default: Long): Long {
            return System.getenv(name)?.toLongOrNull() ?: default
        }
    }
}
