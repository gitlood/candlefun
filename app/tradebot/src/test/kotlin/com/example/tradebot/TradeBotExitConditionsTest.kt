package com.example.tradebot

import com.example.network.interfaces.BinanceTestNetApiService
import com.example.network.model.AccountInfo
import com.example.network.model.Balance
import com.example.network.model.TradeResponse
import com.example.platformutil.AlgoConfig
import com.example.platformutil.BacktestConfig
import com.example.platformutil.EventStudyConfig
import com.example.platformutil.SignalConfig
import com.example.platformutil.model.BotSpec
import com.example.platformutil.model.Candle
import com.example.platformutil.model.ExecutionMode
import com.example.platformutil.model.TradingConfig
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class TradeBotExitConditionsTest {
    @Test
    fun tradeBot_exitsOnStopLoss() = runBlocking {
        val bot = TradeBot(
            RecordingApi(),
            BotSpec(
                name = "bot",
                cfg = cfg(takeProfit = 0.5, stopLoss = 0.1, horizonMinutes = 10),
                patterns = setOf("seq=D0.D0"),
                trade = TradingConfig(mode = ExecutionMode.PAPER, maxOpenPositions = 1)
            )
        )

        val candles0 = listOf(
            candleAt(0L, close = "1.00"),
            candleAt(60_000L, close = "1.00"),
            candleAt(120_000L, close = "1.00"),
            candleAt(180_000L, close = "1.00")
        )
        bot.onCandles(candles0)
        assertEquals(1, bot.openPositions.size)

        val candles1 = listOf(
            candleAt(0L, close = "1.00"),
            candleAt(60_000L, close = "1.00"),
            candleAt(120_000L, close = "1.00"),
            candleAt(180_000L, close = "1.00"),
            candleAt(240_000L, close = "0.80")
        )
        bot.onCandles(candles1)
        assertEquals(0, bot.openPositions.size)
    }

    @Test
    fun tradeBot_exitsOnHorizon() = runBlocking {
        val bot = TradeBot(
            RecordingApi(),
            BotSpec(
                name = "bot",
                cfg = cfg(takeProfit = 0.5, stopLoss = 0.1, horizonMinutes = 1),
                patterns = setOf("seq=D0.D0"),
                trade = TradingConfig(mode = ExecutionMode.PAPER, maxOpenPositions = 1)
            )
        )

        val candles0 = listOf(
            candleAt(0L, close = "1.00"),
            candleAt(60_000L, close = "1.00"),
            candleAt(120_000L, close = "1.00"),
            candleAt(180_000L, close = "1.00")
        )
        bot.onCandles(candles0)
        assertEquals(1, bot.openPositions.size)

        val candles1 = listOf(
            candleAt(0L, close = "1.00"),
            candleAt(60_000L, close = "1.00"),
            candleAt(120_000L, close = "1.00"),
            candleAt(180_000L, close = "1.00"),
            candleAt(240_000L, close = "1.00", high = "BAD")
        )
        bot.onCandles(candles1)
        assertEquals(0, bot.openPositions.size)
    }

    private fun cfg(takeProfit: Double, stopLoss: Double, horizonMinutes: Int): AlgoConfig {
        return AlgoConfig(
            backtest = BacktestConfig(
                takeProfit = takeProfit,
                stopLoss = stopLoss,
                lookbackMinutes = 1,
                horizonMinutes = horizonMinutes
            ),
            eventStudy = EventStudyConfig(patternBars = 2, contextBars = 2),
            signal = SignalConfig(
                ret30mMin = -1.0,
                volumeZMin = -999.0,
                contractionMax = 999.0,
                trendSlopeMin = -999.0
            )
        )
    }

    private fun candleAt(openTime: Long, close: String, high: String = "2.00"): Candle {
        return Candle(
            openTime = openTime,
            open = "1.00",
            high = high,
            low = "0.50",
            close = close,
            volume = "10",
            closeTime = openTime + 60_000L
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

        override suspend fun fetchAccountInfo(): AccountInfo {
            return AccountInfo(
                balances = listOf(Balance(asset = "USDT", free = "1000", locked = "0"))
            )
        }
    }
}
