package com.example.historicaldata

import com.example.historicaldata.util.toCandle
import com.example.platformutil.model.Candle
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Repository for accessing historical candle data from the database.
 */
interface HistoricalDataRepository {
    /**
     * Retrieves all candles from the database.
     *
     * @return A list of all [Candle] objects.
     */
    fun getAllCandles(): List<Candle>

    /**
     * Retrieves the most recent candles, ordered by openTime ascending.
     */
    fun getRecentCandles(limit: Int): List<Candle>

    companion object {
        /**
         * Creates a new instance of the repository and handles database connection.
         */
        fun create(dbPath: String = "binance.db"): HistoricalDataRepository {
            Database.connect("jdbc:sqlite:$dbPath", "org.sqlite.JDBC")
            return HistoricalDataRepositoryImpl()
        }
    }
}

/**
 * Internal implementation of [HistoricalDataRepository] that uses Exposed to query the database.
 */
internal class HistoricalDataRepositoryImpl : HistoricalDataRepository,
    com.example.historicaldata.interfaces.HistoricalDataRepository {
    override fun getAllCandles(): List<Candle> {
        return transaction {
            Candles.selectAll().map { toCandle(it) }
        }
    }

    override fun getRecentCandles(limit: Int): List<Candle> {
        if (limit <= 0) return emptyList()
        return transaction {
            Candles
                .selectAll()
                .orderBy(Candles.openTime, org.jetbrains.exposed.sql.SortOrder.DESC)
                .limit(limit)
                .map { toCandle(it) }
                .reversed()
        }
    }
}
