package com.example.network.futures

import com.example.network.futures.interfaces.FuturesOrderBookService
import com.example.platform.model.OrderBook
import com.example.platform.model.OrderBookEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class FuturesOrderBookAdapterTest {

    @Test
    fun `adapter delegates to futures service`() = runBlocking {
        val service = object : FuturesOrderBookService {
            override suspend fun getDepth(symbol: String, limit: Int): OrderBook {
                return OrderBook(
                    lastUpdateId = 5,
                    bids = listOf(OrderBookEntry(100.0, 1.0)),
                    asks = listOf(OrderBookEntry(101.0, 2.0))
                )
            }
        }
        val adapter = FuturesOrderBookAdapter(service)

        val result = adapter.getDepth("BTCUSDT", 10)

        assertEquals(5L, result.lastUpdateId)
        assertEquals(100.0, result.bids[0].price, 0.0)
    }
}
