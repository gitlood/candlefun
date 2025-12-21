package com.example.historicaldata

import com.example.historicaldata.util.toCandle
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals

class CandleMapperTest {
    @Test
    fun candleMapper_mapsRowToCandle() {
        withTempCandleDb {
            CandleRepositoryImpl().insertKlines(listOf(kline(openTime = 5_000L)))
            val row = transaction { Candles.selectAll().first() }
            val candle = toCandle(row)
            assertEquals(5_000L, candle.openTime)
            assertEquals("1.0", candle.open)
        }
    }
}
