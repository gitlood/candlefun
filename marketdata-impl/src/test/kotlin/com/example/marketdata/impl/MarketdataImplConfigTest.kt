package com.example.marketdata.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketdataImplConfigTest {

    @Test
    fun `default uses env overrides when present`() {
        val config = MarketdataImplConfig.default()

        val envJdbc = System.getenv("CANDLE_DB_JDBC") ?: System.getenv("CANDLE_DB_PATH")
        val expectedJdbc = if (!envJdbc.isNullOrBlank()) {
            if (envJdbc.startsWith("jdbc:sqlite:")) envJdbc else "jdbc:sqlite:$envJdbc"
        } else {
            null
        }
        if (expectedJdbc != null) {
            assertEquals(expectedJdbc, config.jdbcUrl)
        } else {
            assertTrue(config.jdbcUrl.startsWith("jdbc:sqlite:"))
            assertTrue(config.jdbcUrl.contains("candles.db"))
        }

        val expectedInterval = System.getenv("CANDLE_DB_INTERVAL") ?: "1m"
        assertEquals(expectedInterval, config.interval)
    }
}
