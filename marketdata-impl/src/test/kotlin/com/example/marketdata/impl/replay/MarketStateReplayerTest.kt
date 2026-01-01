package com.example.marketdata.impl.replay

import com.example.platform.model.BookLevel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class MarketStateReplayerTest {
    @Test
    fun `replayer parses market state rows`() = runBlocking {
        val temp = Files.createTempFile("marketstate", ".csv").toFile()
        temp.deleteOnExit()
        temp.writeText(
            "symbol,timestampMs,eventTimeMs,bestBidPrice,bestBidQty,bestAskPrice,bestAskQty,midPrice,spread,microPrice,depthImbalance,ofi1s,tradeCount1s,tradeVolume1s,tradeImbalance1s,lastTradePrice,lastTradeQty,lastTradeIsBuyerMaker,vol1s,vol5s,vol10s,vol1m,vol5m,bookUpdateId,bidLevels,askLevels\n" +
                "BTCUSDT,1,1,100,1,101,1,100.5,1,100.5,0,0,0,0,0,100,0.5,true,0,0,0,0,0,10,100:1;99:2,101:1\n"
        )

        val replayer = MarketStateReplayer(temp, speedup = 1_000.0)
        val states = replayer.stream().toList()

        assertEquals(1, states.size)
        assertEquals("BTCUSDT", states[0].symbol)
        assertEquals(BookLevel(price = 100.0, quantity = 1.0), states[0].bidLevels.first())
        assertEquals(BookLevel(price = 101.0, quantity = 1.0), states[0].askLevels.first())
    }
}
