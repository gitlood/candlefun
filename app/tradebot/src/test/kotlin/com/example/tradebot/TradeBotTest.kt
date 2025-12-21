package com.example.tradebot

import com.example.network.interfaces.BinanceTestNetApiService
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

class TradeBotTest {
    @Test
    fun tradeBot_entersAndExitsOnTp() = runBlocking {
        val api = RecordingApi()
        val cfg = AlgoConfig(
            backtest = BacktestConfig(
                takeProfit = 0.5,
                stopLoss = 0.1,
                lookbackMinutes = 1,
                horizonMinutes = 10
            ),
            eventStudy = EventStudyConfig(patternBars = 2),
            signal = SignalConfig(
                ret30mMin = -1.0,
                volumeZMin = -999.0,
                contractionMax = 999.0,
                trendSlopeMin = -999.0
            )
        )
        val spec = BotSpec(
            name = "bot",
            cfg = cfg,
            patterns = setOf("seq=D0.D0|last=D"),
            trade = TradingConfig(mode = ExecutionMode.TESTNET, maxOpenPositions = 1)
        )
        val bot = TradeBot(api, spec)

        val candle0 = candleAt(0L, open = "1.00", close = "1.01")
        val candle1 = candleAt(60_000L, open = "1.00", close = "1.01")
        bot.onCandles(listOf(candle0, candle1))

        assertEquals(1, bot.openPositions.size)
        assertEquals(listOf("BUY"), api.calls.map { it.side })

        val candle2 = candleAt(120_000L, open = "1.00", close = "1.90")
        bot.onCandles(listOf(candle0, candle1, candle2))

        assertEquals(0, bot.openPositions.size)
        assertEquals(listOf("BUY", "SELL"), api.calls.map { it.side })
    }

    private fun candleAt(openTime: Long, open: String, close: String): Candle {
        return Candle(
            openTime = openTime,
            open = open,
            high = "2.00",
            low = "1.00",
            close = close,
            volume = "10",
            closeTime = openTime + 60_000L
        )
    }

    private class RecordingApi : BinanceTestNetApiService {
        data class Call(val side: String, val quantity: String)
        val calls = mutableListOf<Call>()

        override suspend fun createOrder(
            symbol: String,
            side: String,
            type: String,
            quantity: String,
            price: String?,
            timeInForce: String?
        ): TradeResponse {
            calls.add(Call(side = side, quantity = quantity))
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
