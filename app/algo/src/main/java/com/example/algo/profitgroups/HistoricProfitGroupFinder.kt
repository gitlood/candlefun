package com.example.algo.profitgroups

import com.example.platformutil.AlgoConfig
import com.example.platformutil.ProfitGroupConfig
import com.example.platformutil.ProfitGroupSort
import com.example.algo.model.BreakoutGroup
import com.example.algo.model.ReportParams
import com.example.platformutil.model.Candle

class HistoricProfitGroupFinder(
    private val allCandles: List<Candle>
) {

    /**
     * Single source of truth: AlgoConfig.
     * Uses:
     * - cfg.profitGroup.* for profit-group discovery rules + reporting
     * - cfg.backtest.horizonMinutes + cfg.backtest.intervalMillis for bar math
     */
    fun findAndReport(cfg: AlgoConfig): List<BreakoutGroup> {
        return findAndReport(
            profitGroup = cfg.profitGroup,
            horizonMinutes = cfg.backtest.horizonMinutes,
            intervalMillis = cfg.backtest.intervalMillis
        )
    }

    fun findAndReport(
        profitGroup: ProfitGroupConfig,
        horizonMinutes: Int,
        intervalMillis: Long
    ): List<BreakoutGroup> {

        val sorted = allCandles.sortedBy { it.openTime }

        val horizonBars = minutesToBars(horizonMinutes, intervalMillis).coerceAtLeast(1)
        val lookbackBars =
            minutesToBars(profitGroup.localLowLookbackMinutes, intervalMillis).coerceAtLeast(0)

        val finder = BreakoutFinder(sorted)
        val raw = finder.findGroups(
            horizonBars = horizonBars,
            thresholdsPct = profitGroup.thresholds,               // FRACTIONS
            localLowLookbackBars = lookbackBars,
            requireContinuous = profitGroup.requireContinuous,
            intervalMillis = intervalMillis,
            maxDrawdownPctAllowed = profitGroup.maxDrawdownAllowed // FRACTION
        )

        val groups = if (profitGroup.dedupeOverlapping) finder.dedupeOverlaps(raw) else raw

        val finalList = when (profitGroup.sortBy) {
            ProfitGroupSort.TIME_ASC -> groups.sortedBy { it.entryOpenTime }
            ProfitGroupSort.GAIN_DESC -> groups.sortedByDescending { it.gainPctToPeakHigh }
            ProfitGroupSort.DRAWDOWN_ASC -> groups.sortedBy { it.maxDrawdownPct }
        }

        val printer = BreakoutPrinter(profitGroup.zoneId)
        val params = ReportParams(
            horizonMinutes = horizonMinutes,
            horizonBars = horizonBars,
            thresholdsPct = profitGroup.thresholds,
            localLowLookBackMinutes = profitGroup.localLowLookbackMinutes,
            lookBackBars = lookbackBars,
            requireContinuous = profitGroup.requireContinuous,
            dedupeOverlappingWindows = profitGroup.dedupeOverlapping,
            sortBy = profitGroup.sortBy.name,
            intervalMillis = intervalMillis,
            maxGroupsToPrint = profitGroup.maxGroupsToPrint,
            maxDrawdownPctAllowed = profitGroup.maxDrawdownAllowed
        )

        if (profitGroup.printReport) {
            printer.printReport(finalList, sorted.size, raw.size, params)
        }
        return finalList
    }

    private fun minutesToBars(minutes: Int, intervalMillis: Long): Int {
        if (minutes <= 0) return 0
        val millis = minutes.toLong() * 60_000L
        return (millis / intervalMillis).toInt()
    }
}
