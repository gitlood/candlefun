package com.example.network.interfaces

import com.example.platform.model.Kline
import com.example.platform.model.enums.KlineInterval

/**
 * Interface for accessing Binance Public Data Endpoints.
 */
interface BinanceApiService {
    /**
     * Retrieves kline (candlestick) bars for a symbol.
     * Klines are uniquely identified by their open time.
     *
     * @param symbol The trading pair symbol (e.g., "BTCUSDT").
     * @param interval The interval for the klines. Defaults to ONE_MINUTE.
     * @param limit The maximum number of klines to return. Defaults to 1000.
     * @param startTime The start time for the data range (timestamp in ms). Optional.
     * @param endTime The end time for the data range (timestamp in ms). Optional.
     * @return A list of [Kline] objects representing the candlestick data.
     */
    suspend fun getKlines(
        symbol: String,
        interval: KlineInterval = KlineInterval.ONE_MINUTE,
        limit: Int = 1000,
        startTime: Long? = null,
        endTime: Long? = null
    ): List<Kline>
}
