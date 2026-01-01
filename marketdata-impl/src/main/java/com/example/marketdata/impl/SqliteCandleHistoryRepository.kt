package com.example.marketdata.impl

import com.example.marketdata.model.Symbol
import com.example.platform.model.CandleHistoryItem
import com.example.marketdata.repository.CandleHistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.DriverManager

class SqliteCandleHistoryRepository(
    private val jdbcUrl: String,
    private val interval: String = "1m" // fixed since your use case only takes symbol + days
) : CandleHistoryRepository {

    private val mutex = Mutex()
    private var conn: Connection? = null

    private fun ensureConn(): Connection {
        val c = conn
        if (c != null && !c.isClosed) return c
        return DriverManager.getConnection(jdbcUrl).also { conn = it }
    }

    override suspend fun getCandles(
        symbol: Symbol,
        fromOpenTimeInclusive: Long,
        toOpenTimeExclusive: Long?,
        limit: Int?
    ): List<CandleHistoryItem> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val sql = buildString {
                append(
                    """
                    SELECT open_time, open, high, low, close, volume
                    FROM candles
                    WHERE symbol = ? AND interval = ? AND open_time >= ?
                    """.trimIndent()
                )
                if (toOpenTimeExclusive != null) {
                    append(" AND open_time < ?")
                }
                append(" ORDER BY open_time ASC")
                if (limit != null) {
                    append(" LIMIT ?")
                }
            }

            val connection = ensureConn()
            connection.prepareStatement(sql).use { ps ->
                var idx = 1
                ps.setString(idx++, symbol.value)
                ps.setString(idx++, interval)
                ps.setLong(idx++, fromOpenTimeInclusive)
                if (toOpenTimeExclusive != null) {
                    ps.setLong(idx++, toOpenTimeExclusive)
                }
                if (limit != null) {
                    ps.setInt(idx++, limit)
                }

                ps.executeQuery().use { rs ->
                    val out = ArrayList<CandleHistoryItem>(1024)
                    while (rs.next()) {
                        out.add(
                            CandleHistoryItem(
                                openTime = rs.getLong("open_time"),
                                open = rs.getDouble("open"),
                                high = rs.getDouble("high"),
                                low = rs.getDouble("low"),
                                close = rs.getDouble("close"),
                                volume = rs.getDouble("volume")
                            )
                        )
                    }
                    out
                }
            }
        }
    }

    fun close() {
        conn?.close()
        conn = null
    }
}
