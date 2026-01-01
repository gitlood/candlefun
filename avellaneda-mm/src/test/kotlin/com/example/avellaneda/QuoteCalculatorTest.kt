package com.example.avellaneda

import com.example.avellaneda.quotes.QuoteCalculator
import com.example.platform.model.BookLevel
import com.example.platform.model.MarketState
import kotlin.test.Test
import kotlin.test.assertTrue

class QuoteCalculatorTest {
    @Test
    fun `quote calculator enforces quote style`() {
        val config = AvellanedaMmConfig.default("BTCUSDT").copy(
            quoteStyle = QuoteStyle.IMPROVE,
            priceTick = 0.1,
            minSpreadPct = 0.0001,
            volSpreadMultiplier = 0.0
        )
        val calculator = QuoteCalculator(config)
        val state = marketState(mid = 100.0, spread = 0.2)
        val quote = calculator.compute(state, positionQty = 0.0, minSpreadPct = config.minSpreadPct)!!
        assertTrue(quote.bid < quote.ask)
        assertTrue(quote.bid >= state.bestBidPrice!! - config.priceTick)
        assertTrue(quote.ask <= state.bestAskPrice!! + config.priceTick)
    }

    @Test
    fun `inventory skew shifts quotes`() {
        val config = AvellanedaMmConfig.default("BTCUSDT").copy(
            inventorySkew = 0.01,
            maxInventory = 10.0,
            priceTick = 0.1,
            minSpreadPct = 0.0001
        )
        val calculator = QuoteCalculator(config)
        val state = marketState(mid = 100.0, spread = 0.2)
        val flat = calculator.compute(state, positionQty = 0.0, minSpreadPct = config.minSpreadPct)!!
        val long = calculator.compute(state, positionQty = 5.0, minSpreadPct = config.minSpreadPct)!!
        assertTrue(long.bid < flat.bid)
        assertTrue(long.ask < flat.ask)
    }

    private fun marketState(mid: Double, spread: Double): MarketState {
        val bid = mid - spread / 2.0
        val ask = mid + spread / 2.0
        return MarketState(
            symbol = "BTCUSDT",
            timestampMs = 1L,
            eventTimeMs = 1L,
            bestBidPrice = bid,
            bestBidQty = 1.0,
            bestAskPrice = ask,
            bestAskQty = 1.0,
            midPrice = mid,
            spread = spread,
            microPrice = mid,
            depthImbalance = 0.0,
            ofi1s = 0.0,
            tradeCount1s = 0,
            tradeVolume1s = 0.0,
            tradeImbalance1s = 0.0,
            lastTradePrice = mid,
            lastTradeQty = 1.0,
            lastTradeIsBuyerMaker = true,
            vol1s = 0.0,
            vol5s = 0.0,
            vol10s = 0.0,
            vol1m = 0.0,
            vol5m = 0.0,
            bookUpdateId = 1L,
            bidLevels = listOf(BookLevel(bid, 1.0)),
            askLevels = listOf(BookLevel(ask, 1.0))
        )
    }
}
