package com.example.network.interfaces

import com.example.network.model.Kline

/**
 * Interface for accessing Binance Public Data Endpoints.
 */
interface BinanceApiService {
    /**
     * Retrieves kline (candlestick) bars for a symbol.
     * Klines are uniquely identified by their open time.
     *
     * @param symbol The trading pair symbol (e.g., "BTCUSDT").
     * @param interval The interval for the klines (e.g., "1m", "5m", "1h"). Defaults to "5m".
     * @param limit The maximum number of klines to return. Defaults to 1000.
     * @param startTime The start time for the data range (timestamp in ms). Optional.
     * @param endTime The end time for the data range (timestamp in ms). Optional.
     * @return A list of [Kline] objects representing the candlestick data.
     */
    suspend fun getKlines(
        symbol: String,
        interval: String = "5m",
        limit: Int = 1000,
        startTime: Long? = null,
        endTime: Long? = null
    ): List<Kline>
}
