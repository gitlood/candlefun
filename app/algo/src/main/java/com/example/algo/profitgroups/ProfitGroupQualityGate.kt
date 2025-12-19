package com.example.algo.profitgroups

import com.example.platformutil.AlgoConfig
import com.example.algo.model.BreakoutGroup
import kotlin.math.abs

object ProfitGroupQualityGate {

    data class Decision(val proceed: Boolean, val reason: String)

    fun decide(cfg: AlgoConfig, groups: List<BreakoutGroup>): Decision {
        if (groups.isEmpty()) return Decision(false, "No breakout groups.")

        // enough positives for event study to be meaningful
        val minNeeded = cfg.eventStudy.topK * cfg.eventStudy.minPosCount
        if (groups.size < minNeeded) {
            return Decision(false, "Too few groups after dedupe: ${groups.size} < $minNeeded (topK*minPosCount).")
        }

        val topThr = cfg.profitGroup.thresholds.maxOrNull() ?: 0.0
        val topHits = groups.count { it.thresholdHit >= topThr }
        if (topHits < cfg.eventStudy.minPosCount) {
            return Decision(false, "Too few hits at top threshold (${(topThr * 100).toInt()}%): $topHits.")
        }

        // Optional: avoid configs where the “breakouts” are barely moving
        val avgPeak = groups.map { it.gainPctToPeakHigh }.average()
        if (avgPeak < (cfg.profitGroup.thresholds.minOrNull() ?: 0.0) * 0.75) {
            return Decision(false, "Breakouts too weak: avgPeak=${avgPeak * 100}%, below expected.")
        }

        // Optional: reject nasty drawdown behavior
        val avgAbsDd = groups.map { abs(it.maxDrawdownPct) }.average()
        if (avgAbsDd > cfg.profitGroup.maxDrawdownAllowed) {
            return Decision(false, "Avg drawdown too high: ${avgAbsDd * 100}% > allowed ${cfg.profitGroup.maxDrawdownAllowed * 100}%.")
        }

        return Decision(true, "OK")
    }
}
