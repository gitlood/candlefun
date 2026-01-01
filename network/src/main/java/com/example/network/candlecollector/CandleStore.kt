package com.example.network.candlecollector

import com.example.platform.model.Kline
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.concurrent.Executors

class CandleStore(private val jdbcUrl: String) : CandleStorePort, Closeable {
    // Option A: Single-thread dispatcher for DB work
    // We keep a dedicated executor to ensure all DB ops happen on the same thread,
    // satisfying SQLite constraints and serializing access.
    private val dbExecutor = Executors.newSingleThreadExecutor()
    private val dbDispatcher = dbExecutor.asCoroutineDispatcher()

    private var conn: Connection? = null

    override suspend fun init() {
        withContext(dbDispatcher) {
            conn = DriverManager.getConnection(jdbcUrl)
            conn?.createStatement()?.use { stmt ->
                // One huge table? Or one table per interval?
                // Let's use one table with (symbol, interval, open_time) PK
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

    override fun close() {
        try {
            // Close the connection on the same thread it was created/used
            dbExecutor.submit {
                try {
                    conn?.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }.get()
        } finally {
            dbExecutor.shutdown()
        }
    }

    override suspend fun getMinOpenTime(symbol: String, interval: String): Long? = withContext(dbDispatcher) {
        val sql = "SELECT MIN(open_time) FROM candles WHERE symbol = ? AND interval = ?"
        queryLong(sql, symbol, interval)
    }

    override suspend fun getMaxOpenTime(symbol: String, interval: String): Long? = withContext(dbDispatcher) {
        val sql = "SELECT MAX(open_time) FROM candles WHERE symbol = ? AND interval = ?"
        queryLong(sql, symbol, interval)
    }

    override suspend fun getRecentOpenTimes(symbol: String, interval: String, limit: Int): List<Long> =
        withContext(dbDispatcher) {
            val sql = """
                SELECT open_time
                FROM candles
                WHERE symbol = ? AND interval = ?
                ORDER BY open_time DESC
                LIMIT ?
            """.trimIndent()

            conn?.prepareStatement(sql)?.use { ps ->
                ps.setString(1, symbol)
                ps.setString(2, interval)
                ps.setInt(3, limit)
                ps.executeQuery().use { rs ->
                    val out = ArrayList<Long>(limit)
                    while (rs.next()) out.add(rs.getLong(1))
                    return@withContext out
                }
            }
            emptyList()
        }

    override suspend fun deleteOldCandles(symbol: String, interval: String, cutoffTime: Long) {
        withContext(dbDispatcher) {
            val sql = "DELETE FROM candles WHERE symbol = ? AND interval = ? AND open_time < ?"
            conn?.prepareStatement(sql)?.use { ps ->
                ps.setString(1, symbol)
                ps.setString(2, interval)
                ps.setLong(3, cutoffTime)
                ps.executeUpdate()
            }
        }
    }

    private fun queryLong(sql: String, symbol: String, interval: String): Long? {
        conn?.prepareStatement(sql)?.use { ps ->
            ps.setString(1, symbol)
            ps.setString(2, interval)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    val v = rs.getLong(1)
                    if (!rs.wasNull()) return v
                }
            }
        }
        return null
    }

    override suspend fun insertCandles(symbol: String, interval: String, klines: List<Kline>) = withContext(dbDispatcher) {
        if (klines.isEmpty()) return@withContext
        val sql = """
            INSERT OR IGNORE INTO candles (
                symbol, interval, open_time, open, high, low, close, volume,
                close_time, quote_asset_volume, trades, taker_base_vol, taker_quote_vol
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        val connection = conn ?: return@withContext
        connection.autoCommit = false
        try {
            connection.prepareStatement(sql)?.use { ps ->
                for (k in klines) {
                    setParams(ps, symbol, interval, k)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            connection.commit()
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    private fun setParams(ps: PreparedStatement, symbol: String, interval: String, k: Kline) {
        ps.setString(1, symbol)
        ps.setString(2, interval)
        ps.setLong(3, k.openTime)
        ps.setDouble(4, k.open)
        ps.setDouble(5, k.high)
        ps.setDouble(6, k.low)
        ps.setDouble(7, k.close)
        ps.setDouble(8, k.volume)
        ps.setLong(9, k.closeTime)
        ps.setDouble(10, k.quoteAssetVolume)
        ps.setInt(11, k.numberOfTrades)
        ps.setDouble(12, k.takerBuyBaseAssetVolume)
        ps.setDouble(13, k.takerBuyQuoteAssetVolume)
    }
}
