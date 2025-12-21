package com.example.historicaldata

import com.example.historicaldata.interfaces.OrderBookRepository
import com.example.historicaldata.util.OrderBookCollector
import com.example.network.interfaces.BinanceOrderBookService
import com.example.network.model.OrderBookDepth
import com.example.network.model.OrderBookLevel
import com.example.platformutil.model.OrderBookSnapshot
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class OrderBookCollectorTest {
    @Test
    fun orderBookCollector_insertsSnapshot() = runBlocking {
        val service = object : BinanceOrderBookService {
            override suspend fun getDepth(symbol: String, limit: Int): OrderBookDepth {
                return OrderBookDepth(
                    lastUpdateId = 99L,
                    bids = listOf(OrderBookLevel(price = 100.0, quantity = 1.0)),
                    asks = listOf(OrderBookLevel(price = 101.0, quantity = 2.0))
                )
            }
        }
        val inserted = mutableListOf<OrderBookSnapshot>()
        val repo = object : OrderBookRepository {
            override fun insertSnapshot(snapshot: OrderBookSnapshot) {
                inserted.add(snapshot)
            }

            override fun getLatestSnapshot(symbol: String): OrderBookSnapshot? = null

            override fun getSnapshotsSince(symbol: String, sinceTime: Long): List<OrderBookSnapshot> = emptyList()
        }

        val collector = OrderBookCollector(service, repo)
        collector.collectOnce(symbol = "ETHUSDT", limit = 1)
        assertEquals(1, inserted.size)
        assertEquals(99L, inserted.first().updateId)
    }
}
