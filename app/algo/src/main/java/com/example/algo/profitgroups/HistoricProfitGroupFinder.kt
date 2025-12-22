package com.example.algo.profitgroups

import com.example.platformutil.AlgoConfig
import com.example.platformutil.ProfitGroupConfig
import com.example.platformutil.ProfitGroupSort
import com.example.platformutil.OrderBookSignalConfig
import com.example.algo.model.BreakoutGroup
import com.example.algo.model.ReportParams
import com.example.platformutil.model.Candle
import com.example.platformutil.model.OrderBookSnapshot

class HistoricProfitGroupFinder(
    private val allCandles: List<Candle>
) {

    /**
     * Single source of truth: AlgoConfig.
     * Uses:
     * - cfg.profitGroup.* for profit-group discovery rules + reporting
     * - cfg.backtest.horizonMinutes + cfg.backtest.intervalMillis for bar math
     */
    fun findAndReport(
        cfg: AlgoConfig,
        orderBookSnapshots: List<OrderBookSnapshot> = emptyList()
    ): List<BreakoutGroup> {
        return findAndReport(
            profitGroup = cfg.profitGroup,
            horizonMinutes = cfg.backtest.horizonMinutes,
            intervalMillis = cfg.backtest.intervalMillis,
            takeProfitPct = cfg.backtest.takeProfit,
            stopLossPct = cfg.backtest.stopLoss,
            feePerSide = cfg.backtest.feePerSide,
            slippagePerSide = cfg.backtest.slippagePerSide,
            worstCaseIfBothHit = cfg.backtest.worstCaseIfBothHit,
            orderBook = cfg.orderBook,
            orderBookSnapshots = orderBookSnapshots
        )
    }

    fun findAndReport(
        profitGroup: ProfitGroupConfig,
        horizonMinutes: Int,
        intervalMillis: Long,
        takeProfitPct: Double,
        stopLossPct: Double,
        feePerSide: Double,
        slippagePerSide: Double,
        worstCaseIfBothHit: Boolean,
        orderBook: OrderBookSignalConfig = OrderBookSignalConfig(),
        orderBookSnapshots: List<OrderBookSnapshot> = emptyList()
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
            maxDrawdownPctAllowed = profitGroup.maxDrawdownAllowed, // FRACTION
            stopLossPct = stopLossPct,
            worstCaseIfBothHit = worstCaseIfBothHit,
            mode = profitGroup.mode,
            takeProfitPct = takeProfitPct,
            feePerSide = feePerSide,
            slippagePerSide = slippagePerSide
        )

        val gated = applyOrderBookGate(sorted, raw, orderBook, orderBookSnapshots)
        val groups = if (profitGroup.dedupeOverlapping) finder.dedupeOverlaps(gated) else gated

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
            printer.printReport(
                finalList = finalList,
                totalCandles = sorted.size,
                rawFoundCount = raw.size,
                params = params,
                profitGroupMode = profitGroup.mode,
                takeProfitPct = takeProfitPct,
                stopLossPct = stopLossPct,
                feePerSide = feePerSide,
                slippagePerSide = slippagePerSide
            )
        }
        return finalList
    }

    private data class OrderBookSeries(
        val imbalance10: DoubleArray,
        val spreadBps: DoubleArray,
        val available: BooleanArray
    )

    private fun applyOrderBookGate(
        candles: List<Candle>,
        groups: List<BreakoutGroup>,
        orderBook: OrderBookSignalConfig,
        snapshots: List<OrderBookSnapshot>
    ): List<BreakoutGroup> {
        if (!orderBook.enabled || groups.isEmpty() || snapshots.isEmpty()) return groups
        val series = buildOrderBookSeries(candles, snapshots) ?: return groups

        return groups.filter { g ->
            val idx = g.entryIndex
            if (idx < 0 || idx >= series.available.size) return@filter false
            if (!series.available[idx]) return@filter false
            val spread = series.spreadBps[idx]
            val imbalance = series.imbalance10[idx]
            spread <= orderBook.maxSpreadBps && imbalance >= orderBook.minImbalance10
        }
    }

    private fun buildOrderBookSeries(
        candles: List<Candle>,
        snapshots: List<OrderBookSnapshot>
    ): OrderBookSeries? {
        if (snapshots.isEmpty()) return null
        val sorted = snapshots.sortedBy { it.timestamp }
        val n = candles.size
        val imbalance10 = DoubleArray(n) { 0.0 }
        val spreadBps = DoubleArray(n) { Double.POSITIVE_INFINITY }
        val available = BooleanArray(n) { false }

        var snapIdx = 0
        var last: OrderBookSnapshot? = null

        for (i in 0 until n) {
            val t = candles[i].openTime
            while (snapIdx < sorted.size && sorted[snapIdx].timestamp <= t) {
                last = sorted[snapIdx]
                snapIdx++
            }
            if (last != null) {
                val mid = last.midPrice
                val spread = if (mid > 0.0) (last.spread / mid) * 10_000.0 else Double.POSITIVE_INFINITY
                imbalance10[i] = last.imbalance10
                spreadBps[i] = spread
                available[i] = true
            }
        }

        return OrderBookSeries(
            imbalance10 = imbalance10,
            spreadBps = spreadBps,
            available = available
        )
    }

    private fun minutesToBars(minutes: Int, intervalMillis: Long): Int {
        if (minutes <= 0) return 0
        val millis = minutes.toLong() * 60_000L
        return (millis / intervalMillis).toInt()
    }
}
