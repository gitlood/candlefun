package com.example.avellaneda.gates

import com.example.avellaneda.AvellanedaMmConfig
import com.example.platform.model.MarketState
import kotlin.math.abs

class RegimeGate(
    private val config: AvellanedaMmConfig
) {
    private val spreadWindow = SpreadWindow(config.spreadWindowMs)

    fun addSpreadSample(timestampMs: Long, spreadPct: Double) {
        spreadWindow.add(timestampMs, spreadPct)
    }

    fun check(state: MarketState, spreadPct: Double, nowMs: Long): String? {
        val maxSpread = config.maxSpreadPct
        if (maxSpread != null && spreadPct > maxSpread) return "spread_too_wide"

        val avgSpread = spreadWindow.average(nowMs)
        if (avgSpread != null) {
            val minAvg = config.minAvgSpreadPct
            if (minAvg != null && avgSpread < minAvg) return "spread_avg_below_min"
            val maxAvg = config.maxAvgSpreadPct
            if (maxAvg != null && avgSpread > maxAvg) return "spread_avg_above_max"
        }

        val imbalanceLimit = config.maxDepthImbalance
        val imbalance = state.depthImbalance
        if (imbalanceLimit != null && imbalance != null && abs(imbalance) > imbalanceLimit) {
            return "depth_imbalance"
        }

        val minTopDepth = config.minTopDepth
        if (minTopDepth != null) {
            val levels = config.topDepthLevels
            val bidDepth = state.bidLevels.take(levels).sumOf { it.quantity }
            val askDepth = state.askLevels.take(levels).sumOf { it.quantity }
            if (bidDepth + askDepth < minTopDepth) return "depth_collapse"
        }

        val maxVol1s = config.maxVol1s
        if (maxVol1s != null && (state.vol1s ?: 0.0) > maxVol1s) return "vol_1s"
        val maxVol5s = config.maxVol5s
        if (maxVol5s != null && (state.vol5s ?: 0.0) > maxVol5s) return "vol_5s"
        val maxVol10s = config.maxVol10s
        if (maxVol10s != null && (state.vol10s ?: 0.0) > maxVol10s) return "vol_10s"

        val maxTradeImb = config.maxTradeImbalance1s
        if (maxTradeImb != null && state.tradeCount1s >= config.minTradeCount1sForToxicity) {
            if (abs(state.tradeImbalance1s) > maxTradeImb) return "toxic_flow"
        }
        return null
    }
}
