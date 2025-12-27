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
                    scaledDelta.value.compareTo(BigDecimal.ZERO) == 0 -> "no delta after scaling"
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
                            "rejection_reason" to alloc.rejectionReason
                        )
                    }
                )
            )
            summary
        }

        val routed = netSummaries.mapNotNull { summary ->
            if (summary.netDelta.value.compareTo(BigDecimal.ZERO) == 0) return@mapNotNull null

            val weighted = summary.allocations.filter { it.acceptedDelta.value.compareTo(BigDecimal.ZERO) != 0 }
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
