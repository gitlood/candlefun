package com.example.execution.impl

import com.example.execution.domain.StrategyIntent
import kotlin.math.pow

data class EdgeScoreConfig(
    val baseline: Double = 0.5,
    val halfLifeMs: Long = 30_000L,
    val minScore: Double = 0.0,
    val maxScore: Double = 1.0
)

class EdgeScoreEngine(
    private val config: EdgeScoreConfig
) {
    private val scores = mutableMapOf<String, EdgeScoreState>()

    fun updateFromIntents(intents: List<StrategyIntent>, nowMs: Long) {
        if (intents.isEmpty()) {
            decayAll(nowMs)
            return
        }
        val grouped = intents.groupBy { it.strategyId }
        for ((strategyId, items) in grouped) {
            val avg = items.map { it.confidence.coerceIn(config.minScore, config.maxScore) }.average()
            update(strategyId, avg, nowMs)
        }
        decayAll(nowMs, exclude = grouped.keys)
    }

    fun scores(): Map<String, Double> = scores.mapValues { it.value.score }

    private fun update(strategyId: String, sample: Double, nowMs: Long) {
        val clamped = sample.coerceIn(config.minScore, config.maxScore)
        val state = scores[strategyId]
        if (state == null) {
            scores[strategyId] = EdgeScoreState(clamped, nowMs)
            return
        }
        val alpha = alpha(state.lastUpdatedMs, nowMs)
        val next = state.score + alpha * (clamped - state.score)
        scores[strategyId] = EdgeScoreState(next.coerceIn(config.minScore, config.maxScore), nowMs)
    }

    private fun decayAll(nowMs: Long, exclude: Set<String> = emptySet()) {
        if (scores.isEmpty()) return
        for ((strategyId, state) in scores) {
            if (strategyId in exclude) continue
            val alpha = alpha(state.lastUpdatedMs, nowMs)
            val next = state.score + alpha * (config.baseline - state.score)
            scores[strategyId] = EdgeScoreState(next.coerceIn(config.minScore, config.maxScore), nowMs)
        }
    }

    private fun alpha(lastUpdatedMs: Long, nowMs: Long): Double {
        val dt = (nowMs - lastUpdatedMs).coerceAtLeast(0L)
        if (dt == 0L || config.halfLifeMs <= 0L) return 1.0
        val decay = 0.5.pow(dt.toDouble() / config.halfLifeMs.toDouble())
        return 1.0 - decay
    }

    private data class EdgeScoreState(
        val score: Double,
        val lastUpdatedMs: Long
    )

    companion object {
        fun fromEnv(): EdgeScoreEngine {
            val baseline = System.getenv("EDGE_BASELINE_SCORE")?.toDoubleOrNull() ?: 0.5
            val halfLife = System.getenv("EDGE_HALFLIFE_MS")?.toLongOrNull() ?: 30_000L
            val minScore = System.getenv("EDGE_MIN_SCORE")?.toDoubleOrNull() ?: 0.0
            val maxScore = System.getenv("EDGE_MAX_SCORE")?.toDoubleOrNull() ?: 1.0
            return EdgeScoreEngine(
                EdgeScoreConfig(
                    baseline = baseline,
                    halfLifeMs = halfLife,
                    minScore = minScore,
                    maxScore = maxScore
                )
            )
        }
    }
}
