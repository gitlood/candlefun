package com.example.execution.impl

import com.example.account.domain.Price
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
import java.math.BigDecimal

/**
 * Nets intents per symbol, applies regime gates, and allocates risk budget before producing a
 * single routing decision per symbol.
 */
class IntentAllocator(
    private val riskBudget: RiskBudget
) {
    fun allocate(
        intents: List<StrategyIntent>,
        regimes: Map<String, StrategyRegimeState> = emptyMap()
    ): Pair<List<NettingSummary>, List<RoutingDecision>> {
        if (intents.isEmpty()) return emptyList<NettingSummary>() to emptyList()

        val gated = intents.map { intent ->
            val regime = regimes[intent.strategyId]
            val scale = regime?.scale ?: 1.0
            val enabled = regime?.enabled ?: true
            val scaledDelta = if (enabled) intent.desiredDelta * scale else Qty.ZERO
            IntentAllocation(
                intent = intent,
                acceptedDelta = scaledDelta,
                appliedScale = scale,
                rejectionReason = when {
                    enabled.not() -> regime?.reason ?: "regime disabled"
                    scaledDelta.value == BigDecimal.ZERO -> "no delta after scaling"
                    else -> null
                }
            )
        }

        val bySymbol = gated.groupBy { it.intent.symbol }
        val netSummaries = bySymbol.map { (symbol, allocations) ->
            val buys = allocations.filter { it.acceptedDelta.value > BigDecimal.ZERO }
            val sells = allocations.filter { it.acceptedDelta.value < BigDecimal.ZERO }
            val totalBuy = buys.fold(Qty.ZERO) { acc, alloc -> acc + alloc.acceptedDelta }
            val totalSell = sells.fold(Qty.ZERO) { acc, alloc -> acc + Qty(alloc.acceptedDelta.value.abs()) }
            val cross = minOf(totalBuy, totalSell)
            val net = totalBuy - totalSell
            NettingSummary(symbol, totalBuy, totalSell, cross, net, allocations)
        }

        val routed = netSummaries.mapNotNull { summary ->
            if (summary.netDelta.value == BigDecimal.ZERO) return@mapNotNull null

            val weighted = summary.allocations.filter { it.acceptedDelta.value != BigDecimal.ZERO }
                .map { alloc ->
                    val weight = confidenceWeight(
                        confidence = alloc.intent.confidence,
                        exponent = riskBudget.confidenceExponent
                    )
                    val units = riskUnits(alloc.intent, alloc.acceptedDelta)
                    alloc to (units * weight)
                }

            if (weighted.isEmpty()) return@mapNotNull null

            val totalRisk = weighted.sumOf { it.second }
            if (totalRisk <= 0.0) return@mapNotNull null

            val capped = weighted.map { (alloc, score) ->
                val cap = riskBudget.perStrategyCap[alloc.intent.strategyId]
                val cappedScore = if (cap != null && score > cap) cap else score
                alloc to cappedScore
            }

            val capSum = capped.sumOf { it.second }
            if (capSum <= 0.0) return@mapNotNull null

            // Scale net delta by normalized scores to respect risk budget.
            val scale = (riskBudget.total / capSum).coerceAtMost(1.0)
            val targetDelta = Qty(summary.netDelta.value.multiply(BigDecimal.valueOf(scale)))

            val topIntent = capped.maxByOrNull { it.second }?.first?.intent ?: summary.allocations.first().intent
            RoutingDecision(
                symbol = summary.symbol,
                netDelta = targetDelta,
                price = null,
                orderType = OrderType.MARKET,
                preferMaker = topIntent.preferMaker,
                maxSlippageBps = topIntent.maxSlippageBps,
                clientOrderId = "NET-${summary.symbol}-${System.nanoTime()}"
            )
        }

        return netSummaries to routed
    }
}

private operator fun Qty.times(scale: Double): Qty = Qty(this.value.multiply(BigDecimal.valueOf(scale)))

private fun minOf(a: Qty, b: Qty): Qty {
    return if (a.value < b.value) a else b
}
