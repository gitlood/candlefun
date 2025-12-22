package com.example.algo.profitgroups

import com.example.algo.model.BreakoutGroup
import com.example.platformutil.ProfitGroupMode
import com.example.platformutil.model.Candle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class BreakoutFinder(candles: List<Candle>) {

    private val candles = candles.sortedBy { it.openTime }

    fun findGroups(
        horizonBars: Int,
        thresholdsPct: DoubleArray,          // fraction units (0.05, 0.03, 0.02)
        localLowLookbackBars: Int,
        requireContinuous: Boolean,
        intervalMillis: Long,
        maxDrawdownPctAllowed: Double,       // fraction units (0.20 = 20%)
        stopLossPct: Double,                 // fraction units (0.02 = 2%)
        worstCaseIfBothHit: Boolean,
        mode: ProfitGroupMode,
        takeProfitPct: Double,               // fraction units (0.02 = 2%)
        feePerSide: Double,
        slippagePerSide: Double
    ): List<BreakoutGroup> {

        if (candles.size < horizonBars + 2) return emptyList()

        // ✅ thresholds are ALREADY fractions
        val thresholds = thresholdsPct
            .sortedArrayDescending()

        val out = ArrayList<BreakoutGroup>()

        for (i in 0 until (candles.size - horizonBars - 1)) {
            if (!isContinuousWindow(i, horizonBars, requireContinuous, intervalMillis)) continue
            if (!isLocalLow(i, localLowLookbackBars)) continue

            val entryLow = candles[i].low.toDoubleOrNull() ?: continue
            if (entryLow <= 0.0) continue

            val group = evaluateWindow(
                i = i,
                horizonBars = horizonBars,
                thresholds = thresholds,
                entryLow = entryLow,
                intervalMillis = intervalMillis,
                maxDrawdownPctAllowed = maxDrawdownPctAllowed,
                stopLossPct = stopLossPct,
                worstCaseIfBothHit = worstCaseIfBothHit,
                mode = mode,
                takeProfitPct = takeProfitPct,
                feePerSide = feePerSide,
                slippagePerSide = slippagePerSide
            )
            if (group != null) out.add(group)
        }
        return out
    }

    fun dedupeOverlaps(groups: List<BreakoutGroup>): List<BreakoutGroup> {
        if (groups.isEmpty()) return emptyList()
        val sorted = groups.sortedBy { it.entryOpenTime }
        val out = mutableListOf<BreakoutGroup>()
        var current = sorted[0]
        for (g in sorted.drop(1)) {
            if (g.entryOpenTime <= current.windowEndOpenTime) {
                // keep group with higher gain
                if (g.gainPctToPeakHigh > current.gainPctToPeakHigh) current = g
            } else {
                out.add(current)
                current = g
            }
        }
        out.add(current)
        return out
    }


    private fun isContinuousWindow(
        i: Int,
        horizonBars: Int,
        requireContinuous: Boolean,
        intervalMillis: Long
    ): Boolean {
        if (!requireContinuous) return true
        val t0 = candles[i].openTime
        for (k in 1..horizonBars) {
            val expected = t0 + k * intervalMillis
            if (candles[i + k].openTime != expected) return false
        }
        return true
    }

    private fun isLocalLow(i: Int, lookbackBars: Int): Boolean {
        if (lookbackBars <= 0) return true
        val lowI = candles[i].low.toDoubleOrNull() ?: return false
        val start = (i - lookbackBars).coerceAtLeast(0)
        for (k in start until i) {
            val lowK = candles[k].low.toDoubleOrNull() ?: continue
            if (lowK < lowI) return false
        }
        return true
    }

    private fun evaluateWindow(
        i: Int,
        horizonBars: Int,
        thresholds: DoubleArray,             // fraction units (0.05, 0.03, 0.02)
        entryLow: Double,
        intervalMillis: Long,
        maxDrawdownPctAllowed: Double?,      // fraction units (0.20 = 20%)
        stopLossPct: Double,
        worstCaseIfBothHit: Boolean,
        mode: ProfitGroupMode,
        takeProfitPct: Double,
        feePerSide: Double,
        slippagePerSide: Double
    ): BreakoutGroup? {
        val entryOpen = candles[i].open.toDoubleOrNull() ?: return null
        val entryClose = candles[i].close.toDoubleOrNull() ?: return null
        val entryVol = candles[i].volume.toDoubleOrNull() ?: 0.0
        val entryPrice = entryOpen

        val effectiveStopPct = when {
            stopLossPct <= 0.0 && (maxDrawdownPctAllowed ?: 0.0) > 0.0 -> maxDrawdownPctAllowed ?: 0.0
            stopLossPct > 0.0 && (maxDrawdownPctAllowed ?: 0.0) > 0.0 ->
                min(stopLossPct, maxDrawdownPctAllowed ?: stopLossPct)
            else -> max(stopLossPct, 0.0)
        }
        val slPrice =
            if (effectiveStopPct > 0.0) entryPrice * (1.0 - abs(effectiveStopPct)) else Double.NEGATIVE_INFINITY
        val tpPrice =
            if (takeProfitPct > 0.0) entryPrice * (1.0 + abs(takeProfitPct)) else Double.POSITIVE_INFINITY

        var peakHigh = entryPrice
        var peakIdx = i
        var peakVol = entryVol

        var minLow = entryPrice
        var volSum = 0.0
        var tradesSum = 0L

        val horizonClose = candles[i + horizonBars].close.toDoubleOrNull() ?: entryOpen
        var exitPrice = horizonClose
        var exited = false

        // ✅ no doubles as map keys
        val firstHitIndex = IntArray(thresholds.size) { -1 }
        var bestThresholdIdx = -1

        for (k in 0..horizonBars) {
            val c = candles[i + k]
            val h = c.high.toDoubleOrNull() ?: continue
            val l = c.low.toDoubleOrNull() ?: entryLow
            val v = c.volume.toDoubleOrNull() ?: 0.0

            if (h > peakHigh) {
                peakHigh = h
                peakIdx = i + k
                peakVol = v
            }

            minLow = min(minLow, l)
            volSum += v
            tradesSum += c.numberOfTrades.toLong()

            if (!exited) {
                val hitTP = h >= tpPrice
                val hitSL = l <= slPrice
                if (hitTP && hitSL) {
                    if (worstCaseIfBothHit) {
                        exitPrice = slPrice
                    } else {
                        exitPrice = tpPrice
                    }
                    exited = true
                } else if (hitSL) {
                    exitPrice = slPrice
                    exited = true
                } else if (hitTP) {
                    exitPrice = tpPrice
                    exited = true
                }
            }

            val gainNow = (h / entryPrice) - 1.0
            for (t in thresholds.indices) {
                if (firstHitIndex[t] == -1 && gainNow >= thresholds[t]) {
                    firstHitIndex[t] = i + k
                }
            }

            val hitThresholdIdx = thresholds.indexOfFirst { gainNow >= it }
            val hitSL = l <= slPrice

            if (hitSL && (hitThresholdIdx == -1 || worstCaseIfBothHit)) {
                if (mode == ProfitGroupMode.TP_HIT && bestThresholdIdx == -1) return null
                if (mode == ProfitGroupMode.TP_HIT) break
            }

            if (hitThresholdIdx >= 0 && (!hitSL || !worstCaseIfBothHit)) {
                if (bestThresholdIdx == -1 || hitThresholdIdx < bestThresholdIdx) {
                    bestThresholdIdx = hitThresholdIdx
                }
            }
        }

        if (peakIdx == -1 || peakHigh.isInfinite()) return null

        val entryCost = entryPrice * (1.0 + feePerSide + slippagePerSide)
        val exitProceeds = exitPrice * (1.0 - feePerSide - slippagePerSide)
        if (entryCost <= 0.0 || exitProceeds <= 0.0) return null
        val netPct = (exitProceeds / entryCost) - 1.0

        val qualifies = when (mode) {
            ProfitGroupMode.TP_HIT -> bestThresholdIdx != -1
            ProfitGroupMode.NET_POSITIVE -> netPct > 0.0
        }
        if (!qualifies) return null

        val gainToPeak = (peakHigh / entryPrice) - 1.0

        // find the highest threshold hit (thresholds sorted desc)
        val hit = if (bestThresholdIdx >= 0) thresholds[bestThresholdIdx] else 0.0
        val hitIdx = if (bestThresholdIdx >= 0) firstHitIndex[bestThresholdIdx] else -1

        val minutesToHit = if (hitIdx >= 0) {
            ((candles[hitIdx].openTime - candles[i].openTime) / intervalMillis).toInt()
        } else {
            ((candles[peakIdx].openTime - candles[i].openTime) / intervalMillis).toInt()
        }

        val gainToClose = (horizonClose / entryPrice) - 1.0
        val drawdownPct = (minLow / entryPrice) - 1.0

        val minutesToPeak =
            ((candles[peakIdx].openTime - candles[i].openTime) / intervalMillis).toInt()

        return BreakoutGroup(
            entryIndex = i,
            entryOpenTime = candles[i].openTime,
            windowEndOpenTime = candles[i + horizonBars].openTime,
            entryOpen = entryOpen,
            entryLow = entryLow,
            entryClose = entryClose,
            entryVolume = entryVol,
            peakIndex = peakIdx,
            peakOpenTime = candles[peakIdx].openTime,
            peakHigh = peakHigh,
            peakVolume = peakVol,
            minLowInWindow = minLow,
            maxDrawdownPct = drawdownPct,
            gainPctToPeakHigh = gainToPeak,
            horizonClose = horizonClose,
            gainPctToHorizonClose = gainToClose,
            thresholdHit = hit,
            minutesToHit = minutesToHit,
            minutesToPeak = minutesToPeak,
            windowVolumeSum = volSum,
            windowTradesSum = tradesSum
        )
    }
}
