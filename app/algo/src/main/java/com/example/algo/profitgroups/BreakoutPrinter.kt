package com.example.algo.profitgroups

import com.example.algo.model.BreakoutGroup
import com.example.algo.model.ReportParams
import com.example.platformutil.ProfitGroupMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min

class BreakoutPrinter(private val zoneId: ZoneId = ZoneId.systemDefault()) {

    private fun col(s: String, w: Int, right: Boolean = false): String =
        if (right) s.padStart(w) else s.padEnd(w)

    private fun pctStr(x: Double): String = String.format(Locale.US, "%.2f%%", x * 100.0)
    private fun numStr(x: Double): String = String.format(Locale.US, "%.4f", x)
    private fun volStr(x: Double): String = String.format(Locale.US, "%.2f", x)
    fun printReport(
        finalList: List<BreakoutGroup>,
        totalCandles: Int,
        rawFoundCount: Int,
        params: ReportParams,
        profitGroupMode: ProfitGroupMode = ProfitGroupMode.TP_HIT,
        takeProfitPct: Double = 0.0,
        stopLossPct: Double = 0.0,
        feePerSide: Double = 0.0,
        slippagePerSide: Double = 0.0
    ) {
        val utcFmt = DateTimeFormatter.ISO_INSTANT
        val localFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zoneId)

        fun fmt(epochMs: Long): String {
            val inst = Instant.ofEpochMilli(epochMs)
            return "${utcFmt.format(inst)}  (Local: ${localFmt.format(inst)})"
        }

        fun pct(x: Double): String = String.format("%.2f%%", x * 100.0)
        fun fmtLocal(epochMs: Long): String = localFmt.format(Instant.ofEpochMilli(epochMs))

        // ---- Report header ----
        println("=== Historic Profit Group Report (Breakout windows) ===")
        println("Candles loaded: $totalCandles")
        println("Interval: ${params.intervalMillis / 1000}s | Horizon: ${params.horizonMinutes}m (${params.horizonBars} bars)")
        println(
            "Thresholds: ${
                params.thresholdsPct.sortedArrayDescending().joinToString { it.toString() }
            }"
        )
        println("ProfitGroup mode: $profitGroupMode")
        if (profitGroupMode == ProfitGroupMode.NET_POSITIVE) {
            println(
                "Net-positive filter: TP=${pct(takeProfitPct)} SL=${pct(stopLossPct)} " +
                    "costs=${pct(feePerSide)}+${pct(slippagePerSide)} per side"
            )
        }
        println("Local-low lookback: ${params.localLowLookBackMinutes}m (${params.lookBackBars} bars)")
        println("Require continuous minutes: ${params.requireContinuous}")
        println("Entry price used: candle.open (trade simulation)")
        println("De-dupe overlapping windows: ${params.dedupeOverlappingWindows}")
        println("Max Drawdown Allowed: ${params.maxDrawdownPctAllowed * 100}%")
        println("Sort: ${params.sortBy}")
        println("------------------------------------------------------------")
        println("Raw groups found: $rawFoundCount")
        if (params.dedupeOverlappingWindows) println("After de-dupe: ${finalList.size}")
        println("============================================================")

        if (finalList.isEmpty()) {
            println("No breakout groups found with the current settings.")
            return
        }

        // ---- Summary ----
        val byThreshold =
            finalList.groupBy { it.thresholdHit }.toSortedMap(compareByDescending { it })
        println("Counts by threshold hit:")
        for ((thr, list) in byThreshold) {
            println("  >= ${pct(thr)} : ${list.size}")
        }

        val avgGain = finalList.map { it.gainPctToPeakHigh }.average()
        val avgGainClose = finalList.map { it.gainPctToHorizonClose }.average()
        val avgNet = finalList.map { it.netPct }.average()
        val avgDrawdown = finalList.map { it.maxDrawdownPct }.average()
        val avgMinutesToHit = finalList.map { it.minutesToHit }.average()
        val avgMinutesToPeak = finalList.map { it.minutesToPeak }.average()

        val best = finalList.maxByOrNull { it.gainPctToPeakHigh }
        val worst = finalList.minByOrNull { it.gainPctToPeakHigh }

        println("------------------------------------------------------------")
        println("Avg gain to peakHigh: ${pct(avgGain)}")
        println("Avg gain to ${params.horizonMinutes}m close: ${pct(avgGainClose)}")
        println("Avg net (TP/SL + costs): ${pct(avgNet)}")
        println("Avg max drawdown in window: ${pct(avgDrawdown)}")
        println("Avg minutes to hit threshold: ${String.format("%.1f", avgMinutesToHit)}m")
        println("Avg minutes to peak: ${String.format("%.1f", avgMinutesToPeak)}m")
        if (best != null) println("Best gain: ${pct(best.gainPctToPeakHigh)} @ ${fmt(best.entryOpenTime)}")
        if (worst != null) println("Worst gain: ${pct(worst.gainPctToPeakHigh)} @ ${fmt(worst.entryOpenTime)}")
        println("------------------------------------------------------------")
        println(
            "Listing groups (${
                min(
                    finalList.size,
                    params.maxGroupsToPrint
                )
            } of ${finalList.size}):"
        )
        println("")

        // ---- Print all (or capped) ----
        println("")
        val header = buildString {
            append(col("#", 4))
            append(" ")
            append(col("THR", 7, right = true))
            append(" ")
            append(col("gainPk", 8, right = true))
            append(" ")
            append(col("gainH", 8, right = true)) // Horizon close gain
            append(" ")
            append(col("ddMax", 8, right = true))
            append(" ")
            append(col("hit", 4, right = true))
            append(" ")
            append(col("pk", 4, right = true))
            append(" ")
            append(col("entryOpen", 12, right = true))
            append(" ")
            append(col("peakHigh", 12, right = true))
            append(" ")
            append(col("volSum", 12, right = true))
            append(" ")
            append(col("trades", 10, right = true))
            append(" ")
            append(col("entryLocal", 16))
            append(" ")
            append(col("peakLocal", 16))
        }
        println(header)
        println("-".repeat(header.length))

        finalList.take(params.maxGroupsToPrint).forEachIndexed { idx, g ->
            val row = buildString {
                append(col((idx + 1).toString(), 4))
                append(" ")
                append(col(pctStr(g.thresholdHit), 7, right = true))
                append(" ")
                append(col(pctStr(g.gainPctToPeakHigh), 8, right = true))
                append(" ")
                append(col(pctStr(g.gainPctToHorizonClose), 8, right = true))
                append(" ")
                append(col(pctStr(g.maxDrawdownPct), 8, right = true))
                append(" ")
                append(col(g.minutesToHit.toString(), 4, right = true))
                append(" ")
                append(col(g.minutesToPeak.toString(), 4, right = true))
                append(" ")
                append(col(numStr(g.entryOpen), 12, right = true))
                append(" ")
                append(col(numStr(g.peakHigh), 12, right = true))
                append(" ")
                append(col(volStr(g.windowVolumeSum), 12, right = true))
                append(" ")
                append(col(g.windowTradesSum.toString(), 10, right = true))
                append(" ")
                append(col(fmtLocal(g.entryOpenTime), 16))
                append(" ")
                append(col(fmtLocal(g.peakOpenTime), 16))
            }
            println(row)
        }
    }
}
