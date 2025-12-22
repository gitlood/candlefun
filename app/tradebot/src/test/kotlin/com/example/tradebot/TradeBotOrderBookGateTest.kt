package com.example.tradebot

import com.example.network.interfaces.BinanceTestNetApiService
import com.example.network.model.TradeResponse
import com.example.platformutil.AlgoConfig
import com.example.platformutil.BacktestConfig
import com.example.platformutil.EventStudyConfig
import com.example.platformutil.OrderBookSignalConfig
import com.example.platformutil.SignalConfig
import com.example.platformutil.model.BotSpec
import com.example.platformutil.model.Candle
import com.example.platformutil.model.ExecutionMode
import com.example.platformutil.model.OrderBookSnapshot
import com.example.platformutil.model.TradingConfig
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class TradeBotOrderBookGateTest {
    @Test
    fun tradeBot_respectsOrderBookGate() = runBlocking {
        val api = RecordingApi()
        val cfg = AlgoConfig(
            backtest = BacktestConfig(
                takeProfit = 0.5,
                stopLoss = 0.1,
                lookbackMinutes = 1,
                horizonMinutes = 10
            ),
            eventStudy = EventStudyConfig(patternBars = 2, contextBars = 2),
            signal = SignalConfig(
                ret30mMin = -1.0,
                volumeZMin = -999.0,
                contractionMax = 999.0,
                trendSlopeMin = -999.0
            ),
            orderBook = OrderBookSignalConfig(
                enabled = true,
                minImbalance10 = 0.2,
                maxSpreadBps = 50.0
            )
        )
        val spec = BotSpec(
            name = "bot",
            cfg = cfg,
            patterns = setOf("seq=D0.D0"),
            trade = TradingConfig(mode = ExecutionMode.PAPER, maxOpenPositions = 1)
        )
        val bot = TradeBot(api, spec)

        val candles0 = listOf(
            candleAt(0L, close = "1.01"),
            candleAt(60_000L, close = "1.01"),
            candleAt(120_000L, close = "1.01"),
            candleAt(180_000L, close = "1.01")
        )
        val failSnapshot = snapshot(imbalance10 = 0.05, spread = 0.1, mid = 100.0)
        bot.onCandles(candles0, failSnapshot)
        assertEquals(0, bot.openPositions.size)

        val candles1 = listOf(
            candleAt(0L, close = "1.01"),
            candleAt(60_000L, close = "1.01"),
            candleAt(120_000L, close = "1.01"),
            candleAt(180_000L, close = "1.01"),
            candleAt(240_000L, close = "1.02")
        )
        val passSnapshot = snapshot(imbalance10 = 0.5, spread = 0.1, mid = 100.0)
        bot.onCandles(candles1, passSnapshot)
        assertEquals(1, bot.openPositions.size)
    }

    private fun candleAt(openTime: Long, close: String): Candle {
        return Candle(
            openTime = openTime,
            open = "1.00",
            high = "2.00",
            low = "0.50",
            close = close,
            volume = "10",
            closeTime = openTime + 60_000L
        )
    }

    private fun snapshot(imbalance10: Double, spread: Double, mid: Double): OrderBookSnapshot {
        return OrderBookSnapshot(
            timestamp = 1L,
            symbol = "ETHUSDT",
            bestBid = mid - spread / 2.0,
            bestAsk = mid + spread / 2.0,
            midPrice = mid,
            spread = spread,
            bidDepth10 = 10.0,
            askDepth10 = 9.0,
            imbalance10 = imbalance10,
            bidDepth20 = 20.0,
            askDepth20 = 18.0,
            imbalance20 = imbalance10,
            updateId = 7L
        )
    }

    private class RecordingApi : BinanceTestNetApiService {
        override suspend fun createOrder(
            symbol: String,
            side: String,
            type: String,
            quantity: String,
            price: String?,
            timeInForce: String?
        ): TradeResponse {
            return TradeResponse(
                symbol = symbol,
                orderId = 1L,
                clientOrderId = "cid",
                transactTime = 1L,
                price = "1.00"
            )
        }
    }
}
