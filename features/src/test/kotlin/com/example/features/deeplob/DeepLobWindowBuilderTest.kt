package com.example.features.deeplob

import com.example.platform.model.MarketState
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DeepLobWindowBuilderTest {

    @Test
    fun `streamSamples emits after minEvents and caps window`() = runBlocking {
        val builder = DeepLobWindowBuilder(DeepLobConfig(windowSize = 3, minEvents = 3))
        val states = flow {
            emit(state("BTCUSDT", 1L))
            emit(state("BTCUSDT", 2L))
            emit(state("BTCUSDT", 3L))
        }

        val samples = builder.streamSamples(states).toList()

        assertEquals(1, samples.size)
        assertEquals(3L, samples[0].endTimeMs)
        assertEquals(3, samples[0].window.size)
    }

    @Test
    fun `streamSamples emits for each event when minEvents is one`() = runBlocking {
        val builder = DeepLobWindowBuilder(DeepLobConfig(windowSize = 2, minEvents = 1))
        val states = flow {
            emit(state("BTCUSDT", 1L))
            emit(state("BTCUSDT", 2L))
            emit(state("BTCUSDT", 3L))
        }

        val samples = builder.streamSamples(states).toList()

        assertEquals(3, samples.size)
        assertEquals(1, samples[0].window.size)
        assertEquals(2, samples[1].window.size)
        assertEquals(2, samples[2].window.size)
        assertEquals(2L, samples[2].window.first().eventTimeMs)
    }

    @Test
    fun `streamSamples uses timestamp when event time missing`() = runBlocking {
        val builder = DeepLobWindowBuilder(DeepLobConfig(windowSize = 2, minEvents = 1))
        val states = flow {
            emit(state("ETHUSDT", 10L, eventTime = null))
        }

        val samples = builder.streamSamples(states).toList()

        assertEquals(1, samples.size)
        assertEquals(10L, samples[0].endTimeMs)
        assertEquals("ETHUSDT", samples[0].symbol)
    }

    @Test
    fun `streamSamples emits nothing when minEvents not met`() = runBlocking {
        val builder = DeepLobWindowBuilder(DeepLobConfig(windowSize = 5, minEvents = 3))
        val states = flow {
            emit(state("BTCUSDT", 1L))
            emit(state("BTCUSDT", 2L))
        }

        val samples = builder.streamSamples(states).toList()

        assertFalse(samples.isNotEmpty())
    }

    private fun state(symbol: String, ts: Long, eventTime: Long? = ts): MarketState {
        return MarketState(
            symbol = symbol,
            timestampMs = ts,
            eventTimeMs = eventTime,
            bestBidPrice = 1.0,
            bestBidQty = 1.0,
            bestAskPrice = 1.1,
            bestAskQty = 1.0,
            midPrice = 1.05,
            spread = 0.1,
            microPrice = 1.05,
            depthImbalance = 0.0,
            ofi1s = 0.0,
            tradeCount1s = 0,
            tradeVolume1s = 0.0,
            tradeImbalance1s = 0.0,
            lastTradePrice = null,
            lastTradeQty = null,
            lastTradeIsBuyerMaker = null,
            vol1s = null,
            vol5s = null,
            vol10s = null,
            vol1m = null,
            vol5m = null,
            bookUpdateId = 0L,
            bidLevels = emptyList(),
            askLevels = emptyList()
        )
    }
}
