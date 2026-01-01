package com.example.ofi.kukanov

import com.example.platform.model.BookLevel
import com.example.platform.model.MarketState
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class KukanovOfiSignalBuilderTest {
    @Test
    fun `computes kukanov ofi over top level`() = runBlocking {
        val builder = KukanovOfiSignalBuilder(
            KukanovOfiConfig(window = 1.seconds, depthLevels = 1)
        )

        val s1 = state(
            ts = 1_000L,
            bids = listOf(BookLevel(100.0, 5.0)),
            asks = listOf(BookLevel(101.0, 4.0))
        )
        val s2 = state(
            ts = 1_100L,
            bids = listOf(BookLevel(100.5, 6.0)),
            asks = listOf(BookLevel(101.0, 3.0))
        )

        val out = builder.stream(flowOf(s1, s2)).toList()
        val signal = out.last()

        assertEquals(204.0, signal.ofi, 0.0001)
        assertEquals(906.0, signal.depthNotional, 0.0001)
        assertEquals(204.0 / 906.0, signal.normalizedOfi?:0.0, 0.0000001)
    }

    private fun state(
        ts: Long,
        bids: List<BookLevel>,
        asks: List<BookLevel>
    ): MarketState {
        return MarketState(
            symbol = "TEST",
            timestampMs = ts,
            eventTimeMs = ts,
            bestBidPrice = bids.firstOrNull()?.price,
            bestBidQty = bids.firstOrNull()?.quantity,
            bestAskPrice = asks.firstOrNull()?.price,
            bestAskQty = asks.firstOrNull()?.quantity,
            midPrice = null,
            spread = null,
            microPrice = null,
            depthImbalance = null,
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
            bidLevels = bids,
            askLevels = asks
        )
    }
}
