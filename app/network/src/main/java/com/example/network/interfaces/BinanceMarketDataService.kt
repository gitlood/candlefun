package com.example.network.interfaces

import com.example.network.model.Trade
import com.example.network.model.MarketInfo
import com.example.network.model.Ticker

/**
 * Interface for accessing Binance Market Data.
 */
interface BinanceMarketDataService {
    /**
     * Retrieves 24-hour ticker price change statistics for all symbols.
     *
     * @return A list of [Ticker] objects.
     */
    suspend fun get24HrTickers(): List<Ticker>

    /**
     * Retrieves aggregate trade information for a specific symbol.
     *
     * @param symbol The trading pair symbol (e.g., "BTCUSDT").
     * @param fromId The starting trade ID to fetch from. Optional.
     * @param limit The maximum number of trades to return. Defaults to 500.
     * @return A list of [Trade] objects.
     */
    suspend fun getAggTrades(symbol: String, fromId: Long?, limit: Int): List<Trade>

    /**
     * Retrieves current exchange trading rules and symbol information.
     *
     * @return An [MarketInfo] object containing symbol details and filters.
     */
    suspend fun getExchangeInfo(): MarketInfo
}
