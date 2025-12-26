package com.example.network.futures

import com.example.network.BinancePrivateApi
import com.example.network.futures.dto.FuturesAccountDto
import com.example.network.futures.dto.FuturesLeverageDto
import com.example.network.futures.dto.FuturesOrderDto
import com.example.network.futures.dto.FuturesTradeDto
import com.example.network.futures.interfaces.BinanceFuturesTestNetApiService
import com.example.platform.model.enums.OrderSide
import com.example.platform.model.enums.OrderType

internal class BinanceFuturesTestNetApiServiceImpl(
    private val privateApi: BinancePrivateApi
) : BinanceFuturesTestNetApiService {
    override suspend fun createOrder(
        symbol: String,
        side: OrderSide,
        type: OrderType,
        quantity: String,
        price: String?,
        timeInForce: String?
    ): FuturesOrderDto {
        val params = mutableMapOf(
            "symbol" to symbol,
            "side" to side.name,
            "type" to type.name,
            "quantity" to quantity
        )
        if (price != null) params["price"] = price
        if (timeInForce != null) params["timeInForce"] = timeInForce
        return privateApi.post("fapi/v1/order", params)
    }

    override suspend fun cancelOrder(
        symbol: String,
        orderId: Long?,
        clientOrderId: String?
    ): FuturesOrderDto {
        val params = mutableMapOf("symbol" to symbol)
        if (orderId != null) params["orderId"] = orderId.toString()
        if (clientOrderId != null) params["origClientOrderId"] = clientOrderId
        return privateApi.delete("fapi/v1/order", params)
    }

    override suspend fun getOpenOrders(symbol: String?): List<FuturesOrderDto> {
        val params = mutableMapOf<String, String>()
        if (symbol != null) params["symbol"] = symbol
        return privateApi.get("fapi/v1/openOrders", params)
    }

    override suspend fun getAccountInfo(): FuturesAccountDto {
        return privateApi.get("fapi/v2/account")
    }

    override suspend fun getUserTrades(
        symbol: String,
        startTime: Long?,
        endTime: Long?,
        limit: Int?
    ): List<FuturesTradeDto> {
        val params = mutableMapOf("symbol" to symbol)
        if (startTime != null) params["startTime"] = startTime.toString()
        if (endTime != null) params["endTime"] = endTime.toString()
        if (limit != null) params["limit"] = limit.toString()
        return privateApi.get("fapi/v1/userTrades", params)
    }

    override suspend fun setLeverage(symbol: String, leverage: Int): FuturesLeverageDto {
        val params = mutableMapOf(
            "symbol" to symbol,
            "leverage" to leverage.toString()
        )
        return privateApi.post("fapi/v1/leverage", params)
    }
}
