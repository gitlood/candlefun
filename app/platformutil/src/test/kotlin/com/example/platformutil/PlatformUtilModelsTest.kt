package com.example.platformutil

import com.example.platformutil.model.Candle
import com.example.platformutil.model.ExecutionMode
import com.example.platformutil.model.OrderBookSnapshot
import com.example.platformutil.model.TradingConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlatformUtilModelsTest {
    @Test
    fun tradingConfig_defaultsAndBotSpecWireUp() {
        val trade = TradingConfig()
        assertEquals(BINANCE_SYMBOL, trade.symbol)
        assertEquals("5m", trade.candleInterval)
        assertEquals("0.01", trade.quantity)
        assertEquals(1, trade.maxOpenPositions)
        assertEquals(ExecutionMode.TESTNET, trade.mode)

        assertEquals("5m", trade.candleInterval)
        assertTrue(trade.quantity.isNotBlank())
    }

    @Test
    fun candleAndOrderBookSnapshot_dataEquality() {
        val candle = Candle(
            openTime = 1L,
            open = "1",
            high = "2",
            low = "0.5",
            close = "1.5",
            volume = "10",
            closeTime = 2L
        )
        assertEquals(candle, candle.copy())

        val snap = OrderBookSnapshot(
            timestamp = 1L,
            symbol = "ETHUSDT",
            bestBid = 100.0,
            bestAsk = 101.0,
            midPrice = 100.5,
            spread = 1.0,
            bidDepth10 = 10.0,
            askDepth10 = 9.0,
            imbalance10 = 0.1,
            bidDepth20 = 20.0,
            askDepth20 = 18.0,
            imbalance20 = 0.1,
            updateId = 7L
        )
        assertEquals(snap, snap.copy())
    }

    @Test
    fun tradingConfig_valuesAreMutable() {
        val trade = TradingConfig(
            symbol = "BTCUSDT",
            candleInterval = "1m",
            quantity = "0.02",
            maxOpenPositions = 2,
            mode = ExecutionMode.PAPER
        )
        assertEquals("BTCUSDT", trade.symbol)
        assertEquals(2, trade.maxOpenPositions)
        assertEquals(ExecutionMode.PAPER, trade.mode)
    }
}
