package com.example.historicaldata

import kotlin.test.Test
import kotlin.test.assertTrue

class SchemaDefinitionsTest {
    @Test
    fun tableDefinitions_includeExpectedColumns() {
        val candleColumns = Candles.columns.map { it.name }.toSet()
        assertTrue(candleColumns.contains("open_time"))
        assertTrue(candleColumns.contains("close_time"))

        val orderBookColumns = OrderBookSnapshots.columns.map { it.name }.toSet()
        assertTrue(orderBookColumns.contains("best_bid"))
        assertTrue(orderBookColumns.contains("update_id"))
    }
}
