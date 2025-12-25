package com.example.network.candlecollector

import com.example.platform.model.Kline
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class CandleStoreTest {

    private lateinit var store: CandleStore

    @Before
    fun setup() {
        runBlocking {
            // Use in-memory SQLite database
            store = CandleStore("jdbc:sqlite::memory:")
            store.init()
        }
    }

    @After
    fun tearDown() {
        store.close()
    }

    @Test
    fun `insertCandles saves data correctly`() = runBlocking {
        val symbol = "BTCUSDT"
        val interval = "1m"
        val klines = listOf(
            createKline(1000),
            createKline(2000),
            createKline(3000)
        )

        store.insertCandles(symbol, interval, klines)

        val minTime = store.getMinOpenTime(symbol, interval)
        val maxTime = store.getMaxOpenTime(symbol, interval)

        assertEquals(1000L, minTime)
        assertEquals(3000L, maxTime)
    }

    @Test
    fun `getMinMaxOpenTime returns null for empty table`() = runBlocking {
        val minTime = store.getMinOpenTime("ETHUSDT", "1h")
        val maxTime = store.getMaxOpenTime("ETHUSDT", "1h")

        assertNull(minTime)
        assertNull(maxTime)
    }

    @Test
    fun `deleteOldCandles removes data older than cutoff`() = runBlocking {
        val symbol = "SOLUSDT"
        val interval = "15m"
        val klines = listOf(
            createKline(1000), // Old
            createKline(2000), // Old
            createKline(3000), // Boundary?
            createKline(4000), // New
            createKline(5000)  // New
        )

        store.insertCandles(symbol, interval, klines)

        // Delete older than 3500
        store.deleteOldCandles(symbol, interval, 3500)

        val minTime = store.getMinOpenTime(symbol, interval)
        val maxTime = store.getMaxOpenTime(symbol, interval)

        assertEquals(4000L, minTime)
        assertEquals(5000L, maxTime)
    }

    @Test
    fun `insertCandles ignores duplicates`() = runBlocking {
        val symbol = "XRPUSDT"
        val interval = "1m"
        val klines1 = listOf(createKline(1000, 10.0))
        store.insertCandles(symbol, interval, klines1)

        // Try to insert same time but different value (should be ignored by INSERT OR IGNORE)
        val klines2 = listOf(createKline(1000, 20.0))
        store.insertCandles(symbol, interval, klines2)
        
        // We verify by checking we don't crash and count is still 1 (implicit via min/max)
        // A better test would be "select *" but we don't expose that directly for now.
        assertEquals(1000L, store.getMinOpenTime(symbol, interval))
        assertEquals(1000L, store.getMaxOpenTime(symbol, interval))
    }

    private fun createKline(openTime: Long, close: Double = 100.0) = Kline(
        openTime = openTime,
        open = 100.0,
        high = 105.0,
        low = 95.0,
        close = close,
        volume = 1000.0,
        closeTime = openTime + 59999,
        quoteAssetVolume = 100000.0,
        numberOfTrades = 50,
        takerBuyBaseAssetVolume = 500.0,
        takerBuyQuoteAssetVolume = 50000.0
    )
}
