package com.example.network.futures.interfaces

import com.example.network.futures.dto.FuturesAccountDto
import com.example.network.futures.dto.FuturesLeverageDto
import com.example.network.futures.dto.FuturesOrderDto
import com.example.network.futures.dto.FuturesTradeDto
import com.example.platform.model.enums.OrderSide
import com.example.platform.model.enums.OrderType

interface BinanceFuturesTestNetApiService {
    suspend fun createOrder(
        symbol: String,
        side: OrderSide,
        type: OrderType,
        quantity: String,
        price: String? = null,
        timeInForce: String? = null
    ): FuturesOrderDto

    suspend fun cancelOrder(
        symbol: String,
        orderId: Long? = null,
        clientOrderId: String? = null
    ): FuturesOrderDto

    suspend fun getOpenOrders(symbol: String? = null): List<FuturesOrderDto>

    suspend fun getAccountInfo(): FuturesAccountDto

    suspend fun getUserTrades(
        symbol: String,
        startTime: Long? = null,
        endTime: Long? = null,
        limit: Int? = null
    ): List<FuturesTradeDto>

    suspend fun setLeverage(symbol: String, leverage: Int): FuturesLeverageDto
}
