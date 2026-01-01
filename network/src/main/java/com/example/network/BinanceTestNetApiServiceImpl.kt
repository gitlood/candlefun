package com.example.network

import com.example.network.dto.AccountInfoDto
import com.example.network.dto.MyTradeDto
import com.example.network.dto.OrderDto
import com.example.network.dto.TradeResponseDto
import com.example.network.interfaces.BinanceTestNetApiService
import com.example.network.mapper.toDomain
import com.example.platform.model.Account
import com.example.platform.model.OrderResponse
import com.example.platform.model.Trade
import com.example.platform.model.enums.OrderSide
import com.example.platform.model.enums.OrderType

internal class BinanceTestNetApiServiceImpl(
    private val api: BinancePrivateApi
) : BinanceTestNetApiService {

    override suspend fun createOrder(
        symbol: String,
        side: OrderSide,
        type: OrderType,
        quantity: String,
        price: String?,
        timeInForce: String?
    ): OrderResponse {
        val params = mutableMapOf(
            "symbol" to symbol,
            "side" to side.name,
            "type" to type.name,
            "quantity" to quantity
        )
        if (price != null) params["price"] = price
        if (timeInForce != null) params["timeInForce"] = timeInForce

        val dto = api.post<TradeResponseDto>("order", params)
        return dto.toDomain()
    }

    override suspend fun cancelOrder(
        symbol: String,
        orderId: Long?,
        clientOrderId: String?
    ): OrderResponse {
        val params = mutableMapOf("symbol" to symbol)
        if (orderId != null) params["orderId"] = orderId.toString()
        if (clientOrderId != null) params["origClientOrderId"] = clientOrderId

        val dto = api.delete<OrderDto>("order", params)
        return dto.toDomain()
    }

    override suspend fun getOpenOrders(symbol: String?): List<OrderResponse> {
        val params = if (symbol != null) mapOf("symbol" to symbol) else emptyMap()
        val dtos = api.get<List<OrderDto>>("openOrders", params)
        return dtos.map { it.toDomain() }
    }

    override suspend fun getMyTrades(
        symbol: String,
        fromId: Long?,
        startTime: Long?,
        endTime: Long?,
        limit: Int?
    ): List<Trade> {
        val params = mutableMapOf("symbol" to symbol)
        if (fromId != null) params["fromId"] = fromId.toString()
        if (startTime != null) params["startTime"] = startTime.toString()
        if (endTime != null) params["endTime"] = endTime.toString()
        if (limit != null) params["limit"] = limit.toString()

        val dtos = api.get<List<MyTradeDto>>("myTrades", params)
        return dtos.map { it.toDomain() }
    }

    override suspend fun fetchAccountInfo(): Account {
        val dto = api.get<AccountInfoDto>("account")
        return dto.toDomain()
    }
}
