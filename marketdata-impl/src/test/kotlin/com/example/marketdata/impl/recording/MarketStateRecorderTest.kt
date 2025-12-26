package com.example.marketdata.impl.recording

import com.example.platform.model.BookLevel
import com.example.platform.model.MarketState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertTrue

class MarketStateRecorderTest {
    @Ignore
    @Test
    fun `recorder writes header and rows`() = runBlocking {
        val temp = Files.createTempFile("recorder", ".csv").toFile()
        temp.deleteOnExit()
        val recorder = MarketStateRecorder(temp, CoroutineScope(Dispatchers.Unconfined))
        val state = MarketState(
            symbol = "BTCUSDT",
            timestampMs = 1L,
            eventTimeMs = 2L,
            bestBidPrice = 100.0,
            bestBidQty = 1.0,
            bestAskPrice = 101.0,
            bestAskQty = 1.0,
            midPrice = 100.5,
            spread = 1.0,
            microPrice = 100.5,
            depthImbalance = 0.0,
            ofi1s = 0.0,
            tradeCount1s = 1,
            tradeVolume1s = 2.0,
            tradeImbalance1s = 0.0,
            lastTradePrice = 100.0,
            lastTradeQty = 0.5,
            lastTradeIsBuyerMaker = true,
            vol1s = 1.0,
            vol5s = 2.0,
            vol10s = 3.0,
            vol1m = 4.0,
            vol5m = 5.0,
            bookUpdateId = 10L,
            bidLevels = listOf(BookLevel(100.0, 1.0)),
            askLevels = listOf(BookLevel(101.0, 1.0))
        )

        recorder.startSpot(
            repo = object : com.example.marketdata.repository.MarketStateRepository {
                override fun streamMarketState(
                    symbols: List<com.example.marketdata.model.Symbol>,
                    config: com.example.marketdata.model.MarketStateConfig
                ) = flowOf(state)
            },
            symbols = listOf(com.example.marketdata.model.Symbol("BTCUSDT"))
        )
        val found = waitForContent(temp, "BTCUSDT")
        recorder.stop()

        val content = temp.readText()
        assertTrue(content.startsWith("symbol,timestampMs,eventTimeMs"))
        assertTrue(found, "expected recorder to write BTCUSDT row")
        assertTrue(content.contains("BTCUSDT"))
    }

    private suspend fun waitForContent(file: java.io.File, needle: String): Boolean {
        repeat(200) {
            if (file.exists() && file.readText().contains(needle)) return true
            delay(10)
        }
        return false
    }
}
