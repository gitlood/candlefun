package com.example.network.interfaces

import com.example.network.model.Account
import com.example.network.model.OrderResponse

/**
 * Interface for accessing Binance Testnet API endpoints.
 * This service allows placing test orders and retrieving account information on the testnet.
 */
interface BinanceTestNetApiService {
    /**
     * Places a new order on the testnet.
     *
     * @param symbol The trading pair symbol (e.g., "BTCUSDT").
     * @param side The order side ("BUY" or "SELL").
     * @param type The order type (e.g., "LIMIT", "MARKET").
     * @param quantity The quantity of the asset to trade.
     * @param price The price for the order. Required for LIMIT orders.
     * @param timeInForce The time in force policy (e.g., "GTC", "IOC"). Required for LIMIT orders.
     * @return A [OrderResponse] object containing the order details.
     */
    suspend fun createOrder(
        symbol: String,
        side: String,
        type: String,
        quantity: String,
        price: String? = null,
        timeInForce: String? = null
    ): OrderResponse

    /**
     * Retrieves account information including balances and permissions from the testnet.
     *
     * @return An [Account] object containing account details.
     */
    suspend fun fetchAccountInfo(): Account
}
