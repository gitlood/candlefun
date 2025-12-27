package com.example.avellaneda

import com.example.avellaneda.gates.RegimeGate
import com.example.platform.model.BookLevel
import com.example.platform.model.MarketState
import kotlin.test.Test
import kotlin.test.assertEquals

class RegimeGateTest {
    @Test
    fun `gate blocks on depth collapse and volatility`() {
        val config = AvellanedaMmConfig.default("BTCUSDT").copy(
            minTopDepth = 10.0,
            topDepthLevels = 1,
            maxVol1s = 0.02
        )
        val gate = RegimeGate(config)
        val state = marketState(mid = 100.0, spread = 0.2, depthQty = 1.0, vol1s = 0.05)
        val reason = gate.check(state, spreadPct = 0.002, nowMs = 1_000L)
        assertEquals("depth_collapse", reason)
    }

    @Test
    fun `gate blocks on trade imbalance toxicity`() {
        val config = AvellanedaMmConfig.default("BTCUSDT").copy(
            maxTradeImbalance1s = 5.0,
            minTradeCount1sForToxicity = 2
        )
        val gate = RegimeGate(config)
        val state = marketState(
            mid = 100.0,
            spread = 0.2,
            depthQty = 10.0,
            vol1s = 0.0,
            tradeCount = 5,
            tradeImbalance = 10.0
        )
        val reason = gate.check(state, spreadPct = 0.002, nowMs = 1_000L)
        assertEquals("toxic_flow", reason)
    }

    private fun marketState(
        mid: Double,
        spread: Double,
        depthQty: Double,
        vol1s: Double,
        tradeCount: Int = 0,
        tradeImbalance: Double = 0.0
    ): MarketState {
        val bid = mid - spread / 2.0
        val ask = mid + spread / 2.0
        return MarketState(
            symbol = "BTCUSDT",
            timestampMs = 1L,
            eventTimeMs = 1L,
            bestBidPrice = bid,
            bestBidQty = depthQty,
            bestAskPrice = ask,
            bestAskQty = depthQty,
            midPrice = mid,
            spread = spread,
            microPrice = mid,
            depthImbalance = 0.0,
            ofi1s = 0.0,
            tradeCount1s = tradeCount,
            tradeVolume1s = 0.0,
            tradeImbalance1s = tradeImbalance,
            lastTradePrice = mid,
            lastTradeQty = 1.0,
            lastTradeIsBuyerMaker = true,
            vol1s = vol1s,
            vol5s = 0.0,
            vol10s = 0.0,
            vol1m = 0.0,
            vol5m = 0.0,
            bookUpdateId = 1L,
            bidLevels = listOf(BookLevel(bid, depthQty)),
            askLevels = listOf(BookLevel(ask, depthQty))
        )
    }
}
