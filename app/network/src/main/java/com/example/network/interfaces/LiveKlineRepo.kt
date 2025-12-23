package com.example.network.interfaces

import com.example.network.model.LiveCandle

/**
 * Interface for interacting with live kline (candlestick) data streams.
 */
interface LiveKlineRepo {
    /**
     * Streams closed 1-minute candles for the specified symbols.
     *
     * @param symbols The list of trading pairs (e.g., "BTCUSDT") to subscribe to.
     * @param onClosedCandle A callback function invoked when a closed candle is received.
     */
    suspend fun streamClosed1mCandles(
        symbols: List<String>,
        onClosedCandle: suspend (symbol: String, candle: LiveCandle) -> Unit
    )
}
