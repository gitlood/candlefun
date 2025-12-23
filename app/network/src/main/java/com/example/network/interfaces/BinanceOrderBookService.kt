package com.example.network.interfaces

import com.example.network.model.OrderBook

/**
 * Interface for accessing Binance Order Book data.
 */
interface BinanceOrderBookService {
    /**
     * Retrieves the order book depth for a specific symbol.
     *
     * @param symbol The trading pair symbol (e.g., "BTCUSDT").
     * @param limit The number of depth levels to retrieve. Defaults to 100.
     * @return An [OrderBook] object containing bids and asks.
     */
    suspend fun getDepth(
        symbol: String,
        limit: Int = 100
    ): OrderBook
}
