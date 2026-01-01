package com.example.execution.impl

import com.example.account.domain.Qty
import com.example.execution.domain.IntentAllocation
import com.example.execution.domain.NettingSummary
import com.example.execution.domain.RiskBudget
import com.example.execution.domain.RoutingDecision
import com.example.execution.domain.StrategyIntent
import com.example.execution.domain.StrategyRegimeState
import com.example.execution.domain.confidenceWeight
import com.example.execution.domain.riskUnits
import com.example.platform.model.enums.OrderType
import com.example.platform.report.Telemetry
import java.math.BigDecimal

/**
 * Nets intents per symbol, applies regime gates, and allocates risk budget before producing a
 * single routing decision per symbol.
 */
class IntentAllocator(
    val riskBudget: RiskBudget
) {
    fun allocate(
        intents: List<StrategyIntent>,
        regimes: Map<String, StrategyRegimeState> = emptyMap(),
        strategyBudgets: Map<String, Double> = emptyMap()
    ): Pair<List<NettingSummary>, List<RoutingDecision>> {
        if (intents.isEmpty()) return emptyList<NettingSummary>() to emptyList()

        val gated = intents.map { intent ->
            val regime = regimes[intent.strategyId]
            val scale = regime?.scale ?: 1.0
            val enabled = regime?.enabled ?: true
            val scaledDelta = if (enabled) intent.desiredDelta * scale else Qty.ZERO
            val confidenceScale = confidenceWeight(intent.confidence, riskBudget.confidenceExponent)
            val confidenceDelta = scaledDelta * confidenceScale
            IntentAllocation(
                intent = intent,
                acceptedDelta = confidenceDelta,
                appliedScale = scale,
                confidenceScale = confidenceScale,
                rejectionReason = when {
                    enabled.not() -> regime?.reason ?: "regime disabled"
                    scaledDelta.value.compareTo(BigDecimal.ZERO) == 0 -> "no delta after scaling"
                    confidenceScale <= 0.0 -> "confidence throttled"
                    else -> null
                }
            )
        }

        val strategyRequest = gated.groupBy { it.intent.strategyId }.mapValues { (_, allocs) ->
            allocs.sumOf { riskUnits(it.intent, it.acceptedDelta) }
        }
        val strategyScale = strategyRequest.mapValues { (strategyId, requested) ->
            if (requested <= 0.0) return@mapValues 0.0
            val budget = strategyBudgets[strategyId] ?: return@mapValues 1.0
            (budget / requested).coerceAtMost(1.0)
        }
        val budgeted = gated.map { alloc ->
            val scale = strategyScale[alloc.intent.strategyId] ?: 1.0
            alloc.copy(
                acceptedDelta = alloc.acceptedDelta * scale,
                budgetScale = scale
            )
        }

        val totalRisk = budgeted.sumOf { riskUnits(it.intent, it.acceptedDelta) }
        val totalScale = if (riskBudget.total > 0.0 && totalRisk > 0.0) {
            (riskBudget.total / totalRisk).coerceAtMost(1.0)
        } else {
            1.0
        }
        val finalAllocs = if (totalScale < 0.999) {
            budgeted.map { alloc ->
                alloc.copy(
                    acceptedDelta = alloc.acceptedDelta * totalScale,
                    budgetScale = alloc.budgetScale * totalScale
                )
            }
        } else {
            budgeted
        }

        val bySymbol = finalAllocs.groupBy { it.intent.symbol }
        val netSummaries = bySymbol.map { (symbol, allocations) ->
            val buys = allocations.filter { it.acceptedDelta.value > BigDecimal.ZERO }
            val sells = allocations.filter { it.acceptedDelta.value < BigDecimal.ZERO }
            val totalBuy = buys.fold(Qty.ZERO) { acc, alloc -> acc + alloc.acceptedDelta }
            val totalSell = sells.fold(Qty.ZERO) { acc, alloc -> acc + Qty(alloc.acceptedDelta.value.abs()) }
            val cross = minOf(totalBuy, totalSell)
            val net = totalBuy - totalSell
            val summary = NettingSummary(symbol, totalBuy, totalSell, cross, net, allocations)
            Telemetry.emit(
                type = "netting_result",
                tsMs = System.currentTimeMillis(),
                data = mapOf(
                    "symbol" to symbol.value,
                    "total_buy" to totalBuy.value.toDouble(),
                    "total_sell" to totalSell.value.toDouble(),
                    "cross_qty" to cross.value.toDouble(),
                    "net_delta" to net.value.toDouble(),
                    "allocations" to allocations.map { alloc ->
                        mapOf(
                            "strategy_id" to alloc.intent.strategyId,
                            "accepted_delta" to alloc.acceptedDelta.value.toDouble(),
                            "applied_scale" to alloc.appliedScale,
                            "confidence_scale" to alloc.confidenceScale,
                            "budget_scale" to alloc.budgetScale,
                            "rejection_reason" to alloc.rejectionReason
                        )
                    }
                )
            )
            summary
        }

        val routed = netSummaries.mapNotNull { summary ->
            if (summary.netDelta.value.compareTo(BigDecimal.ZERO) == 0) return@mapNotNull null

            val candidates = summary.allocations.filter { it.acceptedDelta.value.compareTo(BigDecimal.ZERO) != 0 }
            if (candidates.isEmpty()) return@mapNotNull null

            val topIntent = candidates.maxByOrNull { it.acceptedDelta.value.abs() }?.intent
                ?: summary.allocations.first().intent
            RoutingDecision(
                symbol = summary.symbol,
                netDelta = summary.netDelta,
                price = null,
                orderType = OrderType.MARKET,
                preferMaker = topIntent.preferMaker,
                maxSlippageBps = topIntent.maxSlippageBps,
                ttlMs = topIntent.ttlMs,
                clientOrderId = "NET-${summary.symbol}-${System.nanoTime()}"
            ).also { decision ->
                Telemetry.emit(
                    type = "routing_decision",
                    tsMs = System.currentTimeMillis(),
                    data = mapOf(
                    "symbol" to decision.symbol.value,
                    "net_delta" to decision.netDelta.value.toDouble(),
                    "order_type" to decision.orderType.name,
                    "prefer_maker" to decision.preferMaker,
                    "max_slippage_bps" to decision.maxSlippageBps,
                        "ttl_ms" to decision.ttlMs,
                        "client_order_id" to decision.clientOrderId
                    )
                )
            }
        }

        return netSummaries to routed
    }
}

private operator fun Qty.times(scale: Double): Qty = Qty(this.value.multiply(BigDecimal.valueOf(scale)))

private fun minOf(a: Qty, b: Qty): Qty {
    return if (a.value < b.value) a else b
}
