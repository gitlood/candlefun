package com.example.execution.impl

import com.example.account.domain.Qty
import com.example.account.domain.Symbol
import com.example.execution.domain.ExecutionGateway
import com.example.execution.domain.IntentStrategy
import com.example.execution.domain.NettingSummary
import com.example.execution.domain.StrategyContext
import com.example.execution.domain.StrategyRegimeState
import com.example.execution.domain.StrategyIntent
import com.example.platform.model.MarketState
import com.example.platform.report.Telemetry
import java.math.BigDecimal

class PortfolioEngine(
    private val gateway: ExecutionGateway,
    private val allocator: IntentAllocator,
    private val policy: ExecutionPolicy,
    private val strategies: List<IntentStrategy>,
    private val positionRefreshMs: Long = 1_000L,
    private val regimeEngine: RegimeEngine = RegimeEngine.fromEnv(),
    private val edgeScoreEngine: EdgeScoreEngine = EdgeScoreEngine.fromEnv(),
    private val riskAllocator: RiskAllocator = RiskAllocator(allocator.riskBudget),
    private val maxTotalNotionalUsd: Double = envDouble("MAX_TOTAL_NOTIONAL_USD", 0.0),
    private val maxSymbolNotionalUsd: Double = envDouble("MAX_SYMBOL_NOTIONAL_USD", 0.0),
    private val maxLatencyMs: Long = envLong("MAX_LATENCY_MS", 0L),
    private val vacuumMmPauseMs: Long = envLong("VACUUM_MM_PAUSE_MS", 10_000L),
    private val ofiDefenseThreshold: Double = envDouble("OFI_MM_DEFENSE_CONF", 0.8),
    private val ofiDefenseScale: Double = envDouble("MM_DEFENSE_SCALE", 0.5),
    private val balanceRefreshMs: Long = envLong("BALANCE_REFRESH_MS", 5_000L),
    private val maxDrawdownPct: Double = envDouble("MAX_DRAWDOWN_PCT", 0.0),
    private val killSwitchCooldownMs: Long = envLong("KILL_SWITCH_COOLDOWN_MS", 60_000L),
    private val equityAssets: Set<String> = envCsv("EQUITY_ASSETS", "USDT,BUSD")
) {
    private val latestStates = mutableMapOf<String, MarketState>()
    private var lastPositionRefreshMs: Long = 0L
    private var positions = emptyMap<Symbol, Qty>()
    private val mmPausedUntilBySymbol = mutableMapOf<String, Long>()
    private var lastBalanceRefreshMs: Long = 0L
    private var equityHighWater: Double? = null
    private var lastEquity: Double? = null
    private var killSwitchUntilMs: Long = 0L

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
        if (isKillSwitchActive(now)) return
        refreshEquity(now)
        if (isKillSwitchActive(now)) return

        val context = StrategyContext(positions = positions, nowMs = now)
        val intents = applySynergy(strategies.flatMap { it.onMarketState(state, context) }, now)
        val regimes = regimeEngine.evaluate(state)
        edgeScoreEngine.updateFromIntents(intents, now)
        val strategyIds = intents.map { it.strategyId }.toSet()
        val budgets = riskAllocator.allocateBudgets(strategyIds, edgeScoreEngine.scores())
        route(intents, now, regimes, budgets)
        policy.onMarketState(state, now)
    }

    suspend fun onExternalIntents(state: MarketState, intents: List<StrategyIntent>) {
        latestStates[state.symbol] = state
        val now = state.eventTimeMs ?: state.timestampMs
        refreshPositions(now)
        if (isKillSwitchActive(now)) return
        refreshEquity(now)
        if (isKillSwitchActive(now)) return
        val regimes = regimeEngine.evaluate(state)
        val adjustedIntents = applySynergy(intents, now)
        edgeScoreEngine.updateFromIntents(adjustedIntents, now)
        val strategyIds = adjustedIntents.map { it.strategyId }.toSet()
        val budgets = riskAllocator.allocateBudgets(strategyIds, edgeScoreEngine.scores())
        route(adjustedIntents, now, regimes, budgets)
        policy.onMarketState(state, now)
    }

    private suspend fun route(
        intents: List<StrategyIntent>,
        nowMs: Long,
        regimes: Map<String, StrategyRegimeState>,
        strategyBudgets: Map<String, Double>
    ) {
        if (intents.isEmpty()) return
        val (summaries, decisions) = allocator.allocate(intents, regimes, strategyBudgets)
        if (decisions.isEmpty()) return
        val summariesBySymbol = summaries.associateBy { it.symbol }
        for (decision in decisions) {
            val symbolState = latestStates[decision.symbol.value] ?: continue
            val summary = summariesBySymbol[decision.symbol]
            val scaled = applyRiskCaps(decision, summary, symbolState, nowMs) ?: continue
            policy.route(scaled, symbolState, nowMs)
        }
    }

    private fun applySynergy(intents: List<StrategyIntent>, nowMs: Long): List<StrategyIntent> {
        if (intents.isEmpty()) return intents
        val vacuumEntries = intents.filter { it.strategyId == "vacuum" && it.reason == "vacuum_entry" }
        for (intent in vacuumEntries) {
            mmPausedUntilBySymbol[intent.symbol.value] = nowMs + vacuumMmPauseMs
        }
        val ofiStrongSymbols = intents.filter { it.strategyId == "ofi_kukanov" && it.confidence >= ofiDefenseThreshold }
            .map { it.symbol }
            .toSet()

        val adjusted = ArrayList<StrategyIntent>(intents.size)
        for (intent in intents) {
            if (intent.strategyId != "avellaneda_mm") {
                adjusted.add(intent)
                continue
            }
            val pauseUntil = mmPausedUntilBySymbol[intent.symbol.value] ?: 0L
            if (pauseUntil > nowMs) {
                continue
            }
            if (intent.symbol in ofiStrongSymbols) {
                val scaled = scaleQty(intent.desiredDelta, ofiDefenseScale)
                if (scaled.value.signum() == 0) continue
                adjusted.add(
                    intent.copy(
                        desiredDelta = scaled,
                        confidence = (intent.confidence * ofiDefenseScale).coerceAtMost(1.0),
                        reason = intent.reason?.let { "$it|ofi_defensive" } ?: "ofi_defensive"
                    )
                )
            } else {
                adjusted.add(intent)
            }
        }
        return adjusted
    }

    private fun scaleQty(qty: Qty, scale: Double): Qty {
        if (scale <= 0.0) return Qty.ZERO
        if (scale >= 0.999) return qty
        return Qty(qty.value.multiply(BigDecimal.valueOf(scale)))
    }

    private suspend fun refreshPositions(nowMs: Long) {
        if (nowMs - lastPositionRefreshMs < positionRefreshMs) return
        val next = gateway.getPositions().associate { it.symbol to it.quantity }
        positions = next
        lastPositionRefreshMs = nowMs
    }

    private fun applyRiskCaps(
        decision: com.example.execution.domain.RoutingDecision,
        summary: NettingSummary?,
        state: MarketState,
        nowMs: Long
    ): com.example.execution.domain.RoutingDecision? {
        val mid = state.midPrice ?: state.microPrice ?: return decision
        if (mid <= 0.0) return decision
        val absDelta = decision.netDelta.value.abs().toDouble()
        val deltaNotional = absDelta * mid
        if (deltaNotional <= 0.0) return null

        var scale = 1.0
        val capReasons = mutableListOf<String>()
        val totalExposure = totalExposureUsd()
        if (maxTotalNotionalUsd > 0.0 && totalExposure + deltaNotional > maxTotalNotionalUsd) {
            val remaining = maxTotalNotionalUsd - totalExposure
            capReasons.add("total_notional")
            if (remaining <= 0.0) {
                emitExecutionDecision(
                    nowMs = nowMs,
                    decision = decision,
                    summary = summary,
                    mid = mid,
                    deltaNotional = deltaNotional,
                    totalExposure = totalExposure,
                    symbolExposure = symbolExposureUsd(decision.symbol),
                    scale = 0.0,
                    finalDelta = null,
                    capReasons = capReasons,
                    dropped = true
                )
                return null
            }
            scale = minOf(scale, remaining / deltaNotional)
        }
        val symbolExposure = symbolExposureUsd(decision.symbol)
        if (maxSymbolNotionalUsd > 0.0 && symbolExposure + deltaNotional > maxSymbolNotionalUsd) {
            val remaining = maxSymbolNotionalUsd - symbolExposure
            capReasons.add("symbol_notional")
            if (remaining <= 0.0) {
                emitExecutionDecision(
                    nowMs = nowMs,
                    decision = decision,
                    summary = summary,
                    mid = mid,
                    deltaNotional = deltaNotional,
                    totalExposure = totalExposure,
                    symbolExposure = symbolExposure,
                    scale = 0.0,
                    finalDelta = null,
                    capReasons = capReasons,
                    dropped = true
                )
                return null
            }
            scale = minOf(scale, remaining / deltaNotional)
        }
        if (scale >= 0.999) return decision
        val scaledDelta = Qty(decision.netDelta.value.multiply(java.math.BigDecimal.valueOf(scale)))
        val updated = decision.copy(netDelta = scaledDelta)
        emitExecutionDecision(
            nowMs = nowMs,
            decision = decision,
            summary = summary,
            mid = mid,
            deltaNotional = deltaNotional,
            totalExposure = totalExposure,
            symbolExposure = symbolExposure,
            scale = scale,
            finalDelta = scaledDelta,
            capReasons = capReasons,
            dropped = false
        )
        return updated
    }

    private fun isKillSwitchActive(nowMs: Long): Boolean {
        return killSwitchUntilMs > nowMs
    }

    private suspend fun refreshEquity(nowMs: Long) {
        if (maxDrawdownPct <= 0.0) return
        if (nowMs - lastBalanceRefreshMs < balanceRefreshMs) return
        lastBalanceRefreshMs = nowMs

        val balances = gateway.getBalances()
        val equity = balances
            .filter { equityAssets.contains(it.asset.value.uppercase()) }
            .sumOf { (it.free + it.locked).toDouble() }
        if (equity <= 0.0) return
        lastEquity = equity
        val highWater = equityHighWater
        if (highWater == null || equity > highWater) {
            equityHighWater = equity
            return
        }
        val drawdown = (highWater - equity) / highWater
        if (drawdown >= maxDrawdownPct) {
            triggerKillSwitch("drawdown", nowMs, mapOf("equity" to equity, "drawdown_pct" to drawdown))
        }
    }

    private suspend fun triggerKillSwitch(reason: String, nowMs: Long, data: Map<String, Any?> = emptyMap()) {
        if (killSwitchUntilMs > nowMs) return
        killSwitchUntilMs = nowMs + killSwitchCooldownMs
        Telemetry.emit(
            type = "kill_switch",
            tsMs = nowMs,
            data = mapOf(
                "reason" to reason,
                "cooldown_ms" to killSwitchCooldownMs,
                "equity" to lastEquity,
                "high_water" to equityHighWater
            ) + data
        )
        val open = gateway.getOpenOrders(null)
        for (order in open) {
            runCatching { gateway.cancelOrder(com.example.execution.domain.OrderCancelRequest(order.symbol, order.orderId)) }
        }
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

    private fun emitExecutionDecision(
        nowMs: Long,
        decision: com.example.execution.domain.RoutingDecision,
        summary: NettingSummary?,
        mid: Double,
        deltaNotional: Double,
        totalExposure: Double,
        symbolExposure: Double,
        scale: Double,
        finalDelta: Qty?,
        capReasons: List<String>,
        dropped: Boolean
    ) {
        if (!dropped && scale >= 0.999) return
        val allocations = summary?.allocations?.map { alloc ->
            val intent = alloc.intent
            mapOf(
                "strategy_id" to intent.strategyId,
                "symbol" to intent.symbol.value,
                "desired_delta" to intent.desiredDelta.value.toDouble(),
                "urgency" to intent.urgency.name,
                "prefer_maker" to intent.preferMaker,
                "ttl_ms" to intent.ttlMs,
                "confidence" to intent.confidence,
                "reason" to intent.reason,
                "accepted_delta" to alloc.acceptedDelta.value.toDouble(),
                "applied_scale" to alloc.appliedScale,
                "confidence_scale" to alloc.confidenceScale,
                "budget_scale" to alloc.budgetScale,
                "rejection_reason" to alloc.rejectionReason
            )
        }.orEmpty()

        Telemetry.emit(
            type = "execution_decision",
            tsMs = nowMs,
            data = mapOf(
                "symbol" to decision.symbol.value,
                "original_net_delta" to decision.netDelta.value.toDouble(),
                "final_net_delta" to finalDelta?.value?.toDouble(),
                "scale" to scale,
                "dropped" to dropped,
                "cap_reasons" to capReasons,
                "mid_price" to mid,
                "delta_notional_usd" to deltaNotional,
                "total_exposure_usd" to totalExposure,
                "symbol_exposure_usd" to symbolExposure,
                "allocations" to allocations
            )
        )
    }

    private companion object {
        fun envDouble(name: String, default: Double): Double {
            return System.getenv(name)?.toDoubleOrNull() ?: default
        }

        fun envLong(name: String, default: Long): Long {
            return System.getenv(name)?.toLongOrNull() ?: default
        }

        fun envCsv(name: String, default: String): Set<String> {
            val raw = System.getenv(name) ?: default
            return raw.split(',')
                .map { it.trim().uppercase() }
                .filter { it.isNotBlank() }
                .toSet()
        }
    }
}
