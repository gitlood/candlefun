package com.example.algo

import kotlin.test.Test
import kotlin.test.assertTrue

class AlgoConfigSweepsTest {
    @Test
    fun grid_respectsRiskRewardConstraints() {
        val cfgs = AlgoConfigSweeps.grid(
            takeProfits = listOf(0.006),
            stopLosses = listOf(0.002, 0.003),
            horizonsMinutes = listOf(40),
            maxDrawdowns = listOf(0.05),
            localLowLookbacks = listOf(0),
            ret30mMins = listOf(-0.01),
            volumeZMins = listOf(-0.5),
            contractionMaxes = listOf(1.1),
            trendSlopeMins = listOf(0.0),
            orderBookEnableds = listOf(false)
        ).toList()

        assertTrue(cfgs.isNotEmpty())
        assertTrue(cfgs.all { it.backtest.takeProfit > it.backtest.stopLoss })
    }
}
