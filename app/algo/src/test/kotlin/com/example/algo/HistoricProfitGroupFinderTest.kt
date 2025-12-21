package com.example.algo

import com.example.algo.profitgroups.HistoricProfitGroupFinder
import com.example.platformutil.AlgoConfig
import com.example.platformutil.BacktestConfig
import com.example.platformutil.ProfitGroupConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class HistoricProfitGroupFinderTest {
    @Test
    fun historicProfitGroupFinder_returnsGroupsWithoutPrinting() {
        val candles = listOf(
            candle(0L, 1.0, 1.0, 1.0, 1.0),
            candle(60_000L, 1.0, 1.06, 1.0, 1.05),
            candle(120_000L, 1.05, 1.1, 1.0, 1.08),
            candle(180_000L, 1.08, 1.08, 1.0, 1.07)
        )
        val cfg = AlgoConfig(
            profitGroup = ProfitGroupConfig(
                thresholds = doubleArrayOf(0.05),
                printReport = false,
                dedupeOverlapping = false
            ),
            backtest = BacktestConfig(horizonMinutes = 2, intervalMillis = 60_000L)
        )
        val finder = HistoricProfitGroupFinder(candles)
        val groups = finder.findAndReport(cfg)
        assertEquals(1, groups.size)
    }

}
