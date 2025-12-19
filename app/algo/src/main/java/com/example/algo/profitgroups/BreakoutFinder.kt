package com.example.algo.profitgroups

import com.example.algo.model.BreakoutGroup
import com.example.platformutil.model.Candle
import kotlin.math.abs
import kotlin.math.min

class BreakoutFinder(candles: List<Candle>) {

    private val candles = candles.sortedBy { it.openTime }

    fun findGroups(
        horizonBars: Int,
        thresholdsPct: DoubleArray,          // fraction units (0.05, 0.03, 0.02)
        localLowLookbackBars: Int,
        requireContinuous: Boolean,
        intervalMillis: Long,
        maxDrawdownPctAllowed: Double        // fraction units (0.20 = 20%)
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
                maxDrawdownPctAllowed = maxDrawdownPctAllowed
            )
            if (group != null) out.add(group)
        }
        return out
    }

    fun dedupeOverlaps(groups: List<BreakoutGroup>): List<BreakoutGroup> {
        if (groups.isEmpty()) return emptyList()
        val sorted = groups.sortedBy { it.entryOpenTime }
        val out = ArrayList<BreakoutGroup>()
        var current = sorted[0]
        for (i in 1 until sorted.size) {
            val g = sorted[i]
            val overlaps = g.entryOpenTime <= current.windowEndOpenTime
            current = if (overlaps) {
                if (g.gainPctToPeakHigh > current.gainPctToPeakHigh) g else current
            } else {
                out.add(current)
                g
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
        maxDrawdownPctAllowed: Double?       // fraction units (0.20 = 20%)
    ): BreakoutGroup? {
        val entryOpen = candles[i].open.toDoubleOrNull() ?: return null
        val entryClose = candles[i].close.toDoubleOrNull() ?: return null
        val entryVol = candles[i].volume.toDoubleOrNull() ?: 0.0

        // ✅ compute drawdown limit ONCE (0.20 -> -0.20)
        val ddLimit = maxDrawdownPctAllowed?.let { -abs(it) }

        var peakHigh = Double.NEGATIVE_INFINITY
        var peakIdx = -1
        var peakVol = 0.0

        var minLow = entryLow
        var volSum = entryVol
        var tradesSum = candles[i].numberOfTrades.toLong()

        // ✅ no doubles as map keys
        val firstHitIndex = IntArray(thresholds.size) { -1 }

        for (k in 1..horizonBars) {
            val c = candles[i + k]
            val h = c.high.toDoubleOrNull() ?: continue
            val l = c.low.toDoubleOrNull() ?: entryLow
            val v = c.volume.toDoubleOrNull() ?: 0.0

            if (h > peakHigh) {
                peakHigh = h
                peakIdx = i + k
                peakVol = v
            }

            // update min low
            minLow = min(minLow, l)

            // ✅ EARLY EXIT: drawdown breached
            if (ddLimit != null) {
                val ddNow = (minLow / entryLow) - 1.0
                if (ddNow < ddLimit) return null
            }

            volSum += v
            tradesSum += c.numberOfTrades.toLong()

            val gainNow = (h / entryLow) - 1.0

            // ✅ updated loop you asked for
            for (t in thresholds.indices) {
                if (firstHitIndex[t] == -1 && gainNow >= thresholds[t]) {
                    firstHitIndex[t] = i + k
                }
            }
        }

        if (peakIdx == -1 || peakHigh.isInfinite()) return null

        val gainToPeak = (peakHigh / entryLow) - 1.0

        // find the highest threshold hit (thresholds sorted desc)
        val hitPos = thresholds.indexOfFirst { gainToPeak >= it }
        if (hitPos == -1) return null

        val hit = thresholds[hitPos]
        val hitIdx = firstHitIndex[hitPos]

        val minutesToHit = if (hitIdx > 0) {
            ((candles[hitIdx].openTime - candles[i].openTime) / intervalMillis).toInt()
        } else {
            ((candles[peakIdx].openTime - candles[i].openTime) / intervalMillis).toInt()
        }

        val horizonClose = candles[i + horizonBars].close.toDoubleOrNull() ?: entryOpen
        val gainToClose = (horizonClose / entryLow) - 1.0
        val drawdownPct = (minLow / entryLow) - 1.0

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
