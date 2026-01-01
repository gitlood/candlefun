package com.example.network.interfaces

import com.example.platform.model.Account
import com.example.platform.model.OrderResponse
import com.example.platform.model.enums.OrderSide
import com.example.platform.model.enums.OrderType

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
        side: OrderSide,
        type: OrderType,
        quantity: String,
        price: String? = null,
        timeInForce: String? = null
    ): OrderResponse

    /**
     * Cancels an existing order on the testnet.
     */
    suspend fun cancelOrder(
        symbol: String,
        orderId: Long? = null,
        clientOrderId: String? = null
    ): OrderResponse

    /**
     * Retrieves open orders for a symbol (or all symbols if null).
     */
    suspend fun getOpenOrders(symbol: String? = null): List<OrderResponse>

    /**
     * Retrieves account trade history for a symbol.
     */
    suspend fun getMyTrades(
        symbol: String,
        fromId: Long? = null,
        startTime: Long? = null,
        endTime: Long? = null,
        limit: Int? = null
    ): List<com.example.platform.model.Trade>

    /**
     * Retrieves account information including balances and permissions from the testnet.
     *
     * @return An [Account] object containing account details.
     */
    suspend fun fetchAccountInfo(): Account
}
