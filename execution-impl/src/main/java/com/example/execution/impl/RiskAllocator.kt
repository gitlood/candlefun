package com.example.execution.impl

import com.example.execution.domain.RiskBudget
import kotlin.math.pow

class RiskAllocator(
    private val budget: RiskBudget
) {
    fun allocateBudgets(
        strategyIds: Set<String>,
        edgeScores: Map<String, Double>
    ): Map<String, Double> {
        if (strategyIds.isEmpty()) return emptyMap()
        val scores = strategyIds.associateWith { id ->
            (edgeScores[id] ?: budget.defaultEdgeScore).coerceIn(0.0, 1.0)
        }
        val weights = scores.mapValues { (_, score) -> score.pow(budget.edgeScoreExponent) }
        val totalWeight = weights.values.sum()
        if (totalWeight <= 0.0 || budget.total <= 0.0) {
            return strategyIds.associateWith { 0.0 }
        }
        val caps = strategyIds.associateWith { id ->
            val absCap = budget.perStrategyCap[id]
            val shareCap = budget.perStrategyMaxShare[id]?.let { budget.total * it }
            when {
                absCap != null && shareCap != null -> minOf(absCap, shareCap)
                absCap != null -> absCap
                shareCap != null -> shareCap
                else -> Double.POSITIVE_INFINITY
            }
        }
        return applyCaps(weights, caps, budget.total)
    }

    private fun applyCaps(
        weights: Map<String, Double>,
        caps: Map<String, Double>,
        total: Double
    ): Map<String, Double> {
        val remaining = weights.toMutableMap()
        val budgets = mutableMapOf<String, Double>()
        var remainingTotal = total
        var remainingWeight = remaining.values.sum()

        while (remaining.isNotEmpty() && remainingWeight > 0.0 && remainingTotal > 0.0) {
            var cappedAny = false
            val iterator = remaining.entries.iterator()
            while (iterator.hasNext()) {
                val (strategyId, weight) = iterator.next()
                val share = weight / remainingWeight
                val proposed = remainingTotal * share
                val cap = caps[strategyId] ?: Double.POSITIVE_INFINITY
                if (proposed > cap) {
                    budgets[strategyId] = cap
                    remainingTotal -= cap
                    remainingWeight -= weight
                    iterator.remove()
                    cappedAny = true
                }
            }
            if (!cappedAny) {
                for ((strategyId, weight) in remaining) {
                    val share = weight / remainingWeight
                    budgets[strategyId] = remainingTotal * share
                }
                remaining.clear()
            }
        }
        if (remainingTotal <= 0.0) {
            for (strategyId in remaining.keys) {
                budgets[strategyId] = 0.0
            }
        }
        return budgets
    }
}
