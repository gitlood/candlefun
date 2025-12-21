package com.example.algo

import com.example.algo.backtest.SignalBacktester
import com.example.platformutil.OrderBookSignalConfig
import com.example.platformutil.SignalConfig
import com.example.algo.model.BacktestFeatures
import com.example.platformutil.model.OrderBookSnapshot
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SignalBacktesterGateTest {
    private val signalConfig = SignalConfig(
        ret30mMin = 0.01,
        volumeZMin = -1.0,
        contractionMax = 1.2,
        trendSlopeMin = 0.0
    )

    private val orderBookConfig = OrderBookSignalConfig(
        enabled = true,
        minImbalance10 = 0.1,
        maxSpreadBps = 10.0
    )

    private val orderBookOk = OrderBookSnapshot(
        timestamp = 0,
        symbol = "ETHUSDT",
        bestBid = 100.0,
        bestAsk = 100.5,
        midPrice = 100.25,
        spread = 0.5,
        bidDepth10 = 50.0,
        askDepth10 = 30.0,
        imbalance10 = 0.25,
        bidDepth20 = 100.0,
        askDepth20 = 70.0,
        imbalance20 = 0.3,
        updateId = 1
    )

    private val orderBookFail = orderBookOk.copy(spread = 5.0, imbalance10 = 0.05, midPrice = 0.0)

    @Test
    fun shouldEnter_declinesBadRet() {
        val features = backtestFeatures(ret30m = -0.1, volumeZ = 0.0, contraction = 0.9, trend = 0.0)
        assertFalse(runGate(features, signalConfig, orderBookConfig, orderBookOk))
    }

    @Test
    fun shouldEnter_honorsOrderBookGate() {
        val features = backtestFeatures(ret30m = 0.02, volumeZ = 0.0, contraction = 0.9, trend = 0.0)
        assertFalse(runGate(features, signalConfig, orderBookConfig, orderBookFail))
        assertTrue(runGate(features, signalConfig, orderBookConfig, orderBookOk))
    }

    @Test
    fun shouldEnter_requiresContraction() {
        val features = backtestFeatures(ret30m = 0.02, volumeZ = 0.0, contraction = 2.0, trend = 0.0)
        assertFalse(runGate(features, signalConfig, orderBookConfig, orderBookOk))
    }

    private fun runGate(
        features: BacktestFeatures,
        signal: SignalConfig,
        orderBook: OrderBookSignalConfig,
        snapshot: OrderBookSnapshot
    ): Boolean {
        val method = SignalBacktester::class.java.getDeclaredMethod(
            "shouldEnter",
            BacktestFeatures::class.java,
            SignalConfig::class.java,
            OrderBookSignalConfig::class.java
        )
        method.isAccessible = true
        val result = method.invoke(SignalBacktester, features.copy(orderBookImbalance10 = snapshot.imbalance10, orderBookSpreadBps = snapshot.spread, orderBookAvailable = true), signal, orderBook)
        return result as Boolean
    }

    private fun backtestFeatures(
        ret30m: Double,
        volumeZ: Double,
        contraction: Double,
        trend: Double
    ): BacktestFeatures {
        return BacktestFeatures(
            trendSlope = trend,
            volStd = 1.0,
            contraction10vLookback = contraction,
            rangeMean = 0.0,
            volumeZ = volumeZ,
            ret5m = 0.0,
            ret15m = 0.0,
            ret30m = ret30m
        )
    }
}
