package com.example.algo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MainHelpersTest {
    @Test
    fun main_parsesSplitAndSplitsCandles() {
        val mainClass = Class.forName("com.example.MainKt")
        val parseSplit = mainClass.getDeclaredMethod("parseSplit", String::class.java)
        parseSplit.isAccessible = true
        val split = parseSplit.invoke(null, "0.6,0.2,0.2")

        val splitCandles = mainClass.getDeclaredMethod("splitCandles", List::class.java, split.javaClass)
        splitCandles.isAccessible = true
        val candles = (0 until 10).map { candle(it * 60_000L) }
        val result = splitCandles.invoke(null, candles, split) as Triple<*, *, *>

        val train = result.first as List<*>
        val validation = result.second as List<*>
        val test = result.third as List<*>
        assertEquals(6, train.size)
        assertEquals(2, validation.size)
        assertEquals(2, test.size)
    }

    @Test
    fun main_loadOrderBookSnapshots_returnsEmptyWhenMissing() {
        val symbol = "MISSINGUSDT"
        val dbFile = java.io.File("orderbook_${symbol}.db")
        if (dbFile.exists()) dbFile.delete()

        val mainClass = Class.forName("com.example.MainKt")
        val loadMethod = mainClass.getDeclaredMethod("loadOrderBookSnapshots", String::class.java, Long::class.javaPrimitiveType)
        loadMethod.isAccessible = true
        val snapshots = loadMethod.invoke(null, symbol, 0L) as List<*>
        assertTrue(snapshots.isEmpty())
    }

}
