package com.example.algo

import com.example.platformutil.AlgoConfig
import com.example.platformutil.BacktestConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlgoConfigSweepsConstraintsTest {
    @Test
    fun grid_respectsRiskRewardAndTpSlOrdering() {
        val base = AlgoConfig(
            backtest = BacktestConfig(
                intervalMillis = 60_000L,
                lookbackMinutes = 10,
                horizonMinutes = 20
            )
        )
        val configs = AlgoConfigSweeps.grid(
            base = base,
            takeProfits = listOf(0.004, 0.006),
            stopLosses = listOf(0.006, 0.004),
            horizonsMinutes = listOf(20)
        ).toList()

        assertTrue(configs.all { it.backtest.takeProfit > it.backtest.stopLoss })
    }

    @Test
    fun grid_tiesProfitGroupThresholdsToTpByDefault() {
        val base = AlgoConfig(backtest = BacktestConfig(intervalMillis = 60_000L))
        val config = AlgoConfigSweeps.grid(
            base = base,
            takeProfits = listOf(0.006),
            stopLosses = listOf(0.003),
            horizonsMinutes = listOf(40),
            maxDrawdowns = listOf(0.05),
            localLowLookbacks = listOf(0),
            ret30mMins = listOf(-0.01),
            volumeZMins = listOf(0.0),
            contractionMaxes = listOf(1.1),
            trendSlopeMins = listOf(0.0),
            orderBookEnableds = listOf(false),
            orderBookMinImbalance10s = listOf(0.05),
            orderBookMaxSpreadBps = listOf(15.0)
        ).first()

        val expected = listOf(0.012, 0.009, 0.006)
        val actual = config.profitGroup.thresholds.toList()
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) ->
            assertTrue(kotlin.math.abs(e - a) < 1e-9)
        }
    }
}
