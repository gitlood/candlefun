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

    companion object {
        /**
         * Creates a new instance of the repository and handles database connection.
         */
        fun create(): HistoricalDataRepository {
            Database.connect("jdbc:sqlite:binance.db", "org.sqlite.JDBC")
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
}
