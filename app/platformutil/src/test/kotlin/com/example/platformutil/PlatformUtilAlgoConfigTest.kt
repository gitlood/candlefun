package com.example.platformutil

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlatformUtilAlgoConfigTest {
    @Test
    fun algoConfig_idIncludesKeySections() {
        val cfg = AlgoConfig(
            profitGroup = ProfitGroupConfig(
                maxDrawdownAllowed = 0.1,
                localLowLookbackMinutes = 3
            ),
            backtest = BacktestConfig(
                takeProfit = 0.01,
                stopLoss = 0.02,
                horizonMinutes = 15
            ),
            signal = SignalConfig(
                ret30mMin = -0.02,
                volumeZMin = 0.1,
                contractionMax = 1.2,
                trendSlopeMin = 0.3
            ),
            orderBook = OrderBookSignalConfig(
                enabled = true,
                minImbalance10 = 0.07,
                maxSpreadBps = 12.0
            )
        )

        val id = cfg.id()
        assertTrue(id.contains("TP=1.00% SL=2.00% HZ=15m"))
        assertTrue(id.contains("DD=10.00% LL=3m"))
        assertTrue(id.contains("SG(ret30=-2.00%"))
        assertTrue(id.contains("volZ>=0.10"))
        assertTrue(id.contains("contr<=1.20"))
        assertTrue(id.contains("slope>=0.30"))
        assertTrue(id.contains("OB(en=true"))
        assertTrue(id.contains("imb10>=0.07"))
        assertTrue(id.contains("spr<=12.00"))
    }

    @Test
    fun profitGroupConfig_equalsUsesThresholdContent() {
        val a = ProfitGroupConfig(thresholds = doubleArrayOf(0.01, 0.02))
        val b = ProfitGroupConfig(thresholds = doubleArrayOf(0.01, 0.02))
        val c = ProfitGroupConfig(thresholds = doubleArrayOf(0.02, 0.03))

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }
}
