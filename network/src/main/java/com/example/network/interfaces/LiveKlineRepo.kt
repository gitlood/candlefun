package com.example.network.interfaces

import com.example.platform.model.LiveCandle
import kotlinx.coroutines.flow.Flow

/**
 * Interface for interacting with live kline (candlestick) data streams.
 */
interface LiveKlineRepo {
    /**
     * Streams closed 1-minute candles for the specified symbols.
     *
     * @param symbols The list of trading pairs (e.g., "BTCUSDT") to subscribe to.
     * @return A [Flow] of Pair(Symbol, LiveCandle) for closed candles.
     */
    fun streamClosed1mCandles(symbols: List<String>): Flow<Pair<String, LiveCandle>>
}
