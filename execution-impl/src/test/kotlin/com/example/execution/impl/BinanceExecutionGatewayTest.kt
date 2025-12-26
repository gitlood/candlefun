package com.example.execution.impl

import com.example.execution.domain.AccountStateRepository
import com.example.execution.domain.OrderCancelRequest
import com.example.execution.domain.OrderRequest
import com.example.execution.domain.Position
import com.example.network.interfaces.BinanceTestNetApiService
import com.example.platform.model.Account
import com.example.platform.model.AccountBalance
import com.example.platform.model.OrderResponse
import com.example.platform.model.Trade
import com.example.platform.model.enums.OrderSide
import com.example.platform.model.enums.OrderType
import org.junit.Assert.assertEquals
import org.junit.Test

class BinanceExecutionGatewayTest {

    @Test
    fun `placeOrder maps response and forwards params`() {
        kotlinx.coroutines.runBlocking {
            val api = RecordingApi()
            val gateway = BinanceExecutionGateway(api, RecordingAccountStateRepo())

            val result = gateway.placeOrder(
                OrderRequest(
                    symbol = "BTCUSDT",
                    side = OrderSide.BUY,
                    type = OrderType.LIMIT,
                    quantity = 1.5,
                    price = 100.0,
                    timeInForce = "GTC",
                    clientOrderId = "cid"
                )
            )

            assertEquals("BTCUSDT", api.lastCreateSymbol)
            assertEquals("1.5", api.lastCreateQty)
            assertEquals("100.0", api.lastCreatePrice)
            assertEquals(OrderSide.BUY, api.lastCreateSide)
            assertEquals(OrderType.LIMIT, api.lastCreateType)

            assertEquals("BTCUSDT", result.symbol)
            assertEquals(1L, result.orderId)
            assertEquals(OrderType.LIMIT, result.type)
            assertEquals(OrderSide.BUY, result.side)
        }
    }

    @Test
    fun `cancelOrder maps response`() {
        kotlinx.coroutines.runBlocking {
            val api = RecordingApi()
            val gateway = BinanceExecutionGateway(api, RecordingAccountStateRepo())

            val result = gateway.cancelOrder(OrderCancelRequest(symbol = "BTCUSDT", orderId = 7L))

            assertEquals("BTCUSDT", api.lastCancelSymbol)
            assertEquals(7L, api.lastCancelOrderId)
            assertEquals(1L, result.orderId)
        }
    }

    @Test
    fun `replaceOrder cancels then places`() {
        kotlinx.coroutines.runBlocking {
            val api = RecordingApi()
            val gateway = BinanceExecutionGateway(api, RecordingAccountStateRepo())

            gateway.replaceOrder(
                cancelRequest = OrderCancelRequest(symbol = "BTCUSDT", orderId = 9L),
                newRequest = OrderRequest(
                    symbol = "BTCUSDT",
                    side = OrderSide.SELL,
                    type = OrderType.MARKET,
                    quantity = 2.0
                )
            )

            assertEquals(listOf("cancel", "place"), api.calls)
        }
    }

    @Test
    fun `getOpenOrders maps list`() {
        kotlinx.coroutines.runBlocking {
            val api = RecordingApi()
            val gateway = BinanceExecutionGateway(api, RecordingAccountStateRepo())

            val result = gateway.getOpenOrders("BTCUSDT")

            assertEquals(1, result.size)
            assertEquals(OrderSide.BUY, result[0].side)
        }
    }

    @Test
    fun `getPositions uses account balances`() {
        kotlinx.coroutines.runBlocking {
            val accountRepo = object : AccountStateRepository {
                override suspend fun getBalances() = listOf(
                    com.example.execution.domain.BalanceSnapshot(asset = "USDT", free = 10.0, locked = 2.0)
                )

                override suspend fun getFills(symbol: String, sinceMs: Long?) =
                    emptyList<com.example.execution.domain.Fill>()
            }
            val gateway = BinanceExecutionGateway(RecordingApi(), accountRepo)

            val positions: List<Position> = gateway.getPositions()

            assertEquals(1, positions.size)
            assertEquals("USDT", positions[0].symbol)
            assertEquals(12.0, positions[0].quantity, 0.0)
        }
    }

    private class RecordingApi : BinanceTestNetApiService {
        var lastCreateSymbol: String? = null
        var lastCreateSide: OrderSide? = null
        var lastCreateType: OrderType? = null
        var lastCreateQty: String? = null
        var lastCreatePrice: String? = null
        var lastCancelSymbol: String? = null
        var lastCancelOrderId: Long? = null
        val calls = mutableListOf<String>()

        override suspend fun createOrder(
            symbol: String,
            side: OrderSide,
            type: OrderType,
            quantity: String,
            price: String?,
            timeInForce: String?
        ): OrderResponse {
            calls.add("place")
            lastCreateSymbol = symbol
            lastCreateSide = side
            lastCreateType = type
            lastCreateQty = quantity
            lastCreatePrice = price
            return OrderResponse(
                symbol = symbol,
                orderId = 1L,
                clientOrderId = "client",
                transactTime = 10L,
                price = price?.toDouble() ?: 0.0,
                origQty = quantity.toDouble(),
                executedQty = 0.0,
                cummulativeQuoteQty = 0.0,
                status = "NEW",
                timeInForce = timeInForce ?: "GTC",
                type = type.name,
                side = side.name
            )
        }

        override suspend fun cancelOrder(
            symbol: String,
            orderId: Long?,
            clientOrderId: String?
        ): OrderResponse {
            calls.add("cancel")
            lastCancelSymbol = symbol
            lastCancelOrderId = orderId
            return OrderResponse(
                symbol = symbol,
                orderId = 1L,
                clientOrderId = "client",
                transactTime = 10L,
                price = 0.0,
                origQty = 0.0,
                executedQty = 0.0,
                cummulativeQuoteQty = 0.0,
                status = "CANCELED",
                timeInForce = "GTC",
                type = "LIMIT",
                side = "BUY"
            )
        }

        override suspend fun getOpenOrders(symbol: String?): List<OrderResponse> {
            return listOf(
                OrderResponse(
                    symbol = symbol ?: "BTCUSDT",
                    orderId = 1L,
                    clientOrderId = "client",
                    transactTime = 10L,
                    price = 100.0,
                    origQty = 1.0,
                    executedQty = 0.0,
                    cummulativeQuoteQty = 0.0,
                    status = "NEW",
                    timeInForce = "GTC",
                    type = "LIMIT",
                    side = "BUY"
                )
            )
        }

        override suspend fun getMyTrades(
            symbol: String,
            fromId: Long?,
            startTime: Long?,
            endTime: Long?,
            limit: Int?
        ): List<Trade> = emptyList()

        override suspend fun fetchAccountInfo(): Account {
            return Account(balances = listOf(AccountBalance(asset = "USDT", free = 1.0, locked = 2.0)))
        }
    }

    private class RecordingAccountStateRepo : AccountStateRepository {
        override suspend fun getBalances() = listOf(
            com.example.execution.domain.BalanceSnapshot(asset = "USDT", free = 1.0, locked = 2.0)
        )

        override suspend fun getFills(symbol: String, sinceMs: Long?) = emptyList<com.example.execution.domain.Fill>()
    }
}
