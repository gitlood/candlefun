package com.example.historicaldata

import com.example.platformutil.DEFAULT_INTERVALS
import com.example.platformutil.DEFAULT_SYMBOLS
import com.example.platformutil.candleDbPath
import kotlin.test.Test
import kotlin.test.assertEquals

class HistoricalMainTest {
    @Test
    fun defaultCandleJobs_matchSymbolsAndIntervals() {
        val method = Class.forName("com.example.historicaldata.MainKt")
            .getDeclaredMethod("defaultCandleJobs")
        method.isAccessible = true
        val jobs = method.invoke(null) as List<*>

        val expectedCount = DEFAULT_SYMBOLS.size * DEFAULT_INTERVALS.size
        assertEquals(expectedCount, jobs.size)

        val any = jobs.first() as com.example.platformutil.CandleJob
        assertEquals(candleDbPath(any.symbol, any.interval), any.dbPath)
    }

    @Test
    fun orderBookMain_acceptsZeroIterations() {
        runOrderBookCollector(arrayOf("--iterations=0"))
    }
}
