package com.example.execution.impl

import com.example.execution.domain.RiskBudget

object RiskBudgetEnv {
    fun fromEnv(
        defaultTotal: Double,
        defaultShares: Map<String, Double> = emptyMap()
    ): RiskBudget {
        val total = envDouble("RISK_BUDGET_TOTAL", defaultTotal)
        val confidenceExponent = envDouble("RISK_CONFIDENCE_EXP", 2.0)
        val edgeExponent = envDouble("RISK_EDGE_EXP", 2.0)
        val defaultEdgeScore = envDouble("RISK_DEFAULT_EDGE_SCORE", 0.5)
        val perStrategyMaxShare = defaultShares.mapValues { (id, defaultShare) ->
            envDouble("RISK_MAX_SHARE_${envKey(id)}", defaultShare)
        }.filterValues { it > 0.0 }
        val perStrategyCap = defaultShares.mapValues { (id, _) ->
            envDouble("RISK_CAP_${envKey(id)}", 0.0)
        }.filterValues { it > 0.0 }
        return RiskBudget(
            total = total,
            perStrategyCap = perStrategyCap,
            perStrategyMaxShare = perStrategyMaxShare,
            defaultEdgeScore = defaultEdgeScore,
            confidenceExponent = confidenceExponent,
            edgeScoreExponent = edgeExponent
        )
    }

    private fun envKey(strategyId: String): String {
        return strategyId.uppercase().replace(Regex("[^A-Z0-9]+"), "_")
    }

    private fun envDouble(name: String, default: Double): Double {
        return System.getenv(name)?.toDoubleOrNull() ?: default
    }
}
