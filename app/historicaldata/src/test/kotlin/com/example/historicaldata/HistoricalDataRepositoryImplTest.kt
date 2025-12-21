package com.example.historicaldata

import kotlin.test.Test
import kotlin.test.assertEquals

class HistoricalDataRepositoryImplTest {
    @Test
    fun historicalDataRepository_recentCandlesAreAscending() {
        withTempCandleDb {
            val repo = HistoricalDataRepositoryImpl()
            val klines = listOf(
                kline(openTime = 1_000L),
                kline(openTime = 2_000L),
                kline(openTime = 3_000L)
            )
            CandleRepositoryImpl().insertKlines(klines)

            val recent = repo.getRecentCandles(2)
            assertEquals(listOf(2_000L, 3_000L), recent.map { it.openTime })
        }
    }

    @Test
    fun historicalDataRepositoryImpl_returnsEmptyForNonPositiveLimit() {
        val repo: com.example.historicaldata.interfaces.HistoricalDataRepository = HistoricalDataRepositoryImpl()
        assertEquals(emptyList(), repo.getRecentCandles(0))
    }

}
