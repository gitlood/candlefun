package com.example.marketdata.impl

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import kotlin.io.path.absolutePathString

class SqliteCandleHistoryRepositoryTest {
    private lateinit var dbPath: String

    @Before
    fun setup() {
        dbPath = Files.createTempFile("candles", ".db").absolutePathString()
        createTable()
    }

    @After
    fun tearDown() {
        Files.deleteIfExists(java.nio.file.Path.of(dbPath))
    }

    @Test
    fun `getCandlesFrom returns ordered rows and filters symbol`() = runBlocking {
        insertRow("BTCUSDT", "1m", 3000L)
        insertRow("BTCUSDT", "1m", 1000L)
        insertRow("BTCUSDT", "1m", 2000L)
        insertRow("ETHUSDT", "1m", 1500L)

        val repo = SqliteCandleHistoryRepository("jdbc:sqlite:$dbPath", interval = "1m")
        val result = repo.getCandlesFrom("BTCUSDT", fromOpenTimeInclusive = 1500L)
        repo.close()

        assertEquals(2, result.size)
        assertEquals(2000L, result[0].openTime)
        assertEquals(3000L, result[1].openTime)
    }

    @Test
    fun `getCandlesFrom respects interval`() = runBlocking {
        insertRow("BTCUSDT", "1m", 1000L)
        insertRow("BTCUSDT", "5m", 1000L)

        val repo = SqliteCandleHistoryRepository("jdbc:sqlite:$dbPath", interval = "5m")
        val result = repo.getCandlesFrom("BTCUSDT", fromOpenTimeInclusive = 0L)
        repo.close()

        assertEquals(1, result.size)
        assertEquals(1000L, result[0].openTime)
    }

    @Test
    fun `getCandlesFrom returns empty when no rows`() = runBlocking {
        val repo = SqliteCandleHistoryRepository("jdbc:sqlite:$dbPath", interval = "1m")
        val result = repo.getCandlesFrom("BTCUSDT", fromOpenTimeInclusive = 0L)
        repo.close()

        assertTrue(result.isEmpty())
    }

    private fun createTable() {
        val jdbcUrl = "jdbc:sqlite:$dbPath"
        java.sql.DriverManager.getConnection(jdbcUrl).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS candles (
                        symbol TEXT NOT NULL,
                        interval TEXT NOT NULL,
                        open_time INTEGER NOT NULL,
                        open REAL,
                        high REAL,
                        low REAL,
                        close REAL,
                        volume REAL,
                        close_time INTEGER,
                        quote_asset_volume REAL,
                        trades INTEGER,
                        taker_base_vol REAL,
                        taker_quote_vol REAL,
                        PRIMARY KEY (symbol, interval, open_time)
                    )
                    """.trimIndent()
                )
            }
        }
    }

    private fun insertRow(symbol: String, interval: String, openTime: Long) {
        val jdbcUrl = "jdbc:sqlite:$dbPath"
        java.sql.DriverManager.getConnection(jdbcUrl).use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO candles (symbol, interval, open_time, open, high, low, close, volume)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, symbol)
                ps.setString(2, interval)
                ps.setLong(3, openTime)
                ps.setDouble(4, 1.0)
                ps.setDouble(5, 2.0)
                ps.setDouble(6, 0.5)
                ps.setDouble(7, 1.5)
                ps.setDouble(8, 10.0)
                ps.executeUpdate()
            }
        }
    }
}
