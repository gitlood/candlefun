package com.example.historicaldata

import com.example.historicaldata.interfaces.CandleRepository
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CandleRepositoryImplTest {
    @Test
    fun candleRepository_insertsAndQueries() {
        withTempCandleDb { dbFile ->
            val repo: CandleRepository = CandleRepositoryImpl()
            assertTrue(repo.isDatabaseEmpty())

            val klines = listOf(
                kline(openTime = 1_000L),
                kline(openTime = 2_000L)
            )
            repo.insertKlines(klines)

            assertFalse(repo.isDatabaseEmpty())
            assertEquals(2_000L, repo.getLatestCandleOpenTime())
            assertEquals(1_000L, repo.getOldestCandleOpenTime())

            dbFile.delete()
        }
    }

    @Test
    fun candleRepository_cleanupOldCandlesRemovesHistoricalData() {
        withTempCandleDb {
            val repo: CandleRepository = CandleRepositoryImpl()
            val now = System.currentTimeMillis()
            val old = now - TimeUnit.DAYS.toMillis(271)
            repo.insertKlines(
                listOf(
                    kline(openTime = old),
                    kline(openTime = now)
                )
            )

            val removed = repo.cleanupOldCandles()
            assertTrue(removed >= 1)

            val remaining = transaction { Candles.selectAll().count() }
            assertEquals(1, remaining)
        }
    }

}
