package com.example.historicaldata.interfaces

import com.example.platformutil.model.Candle

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
}
