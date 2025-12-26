package com.example.execution.impl

import com.example.execution.domain.BalanceSnapshot
import com.example.execution.domain.Fill
import com.example.network.interfaces.BinanceTestNetApiService
import com.example.platform.model.Account
import com.example.platform.model.AccountBalance
import com.example.platform.model.OrderResponse
import com.example.platform.model.Trade
import com.example.platform.model.enums.OrderSide
import com.example.platform.model.enums.OrderType
import org.junit.Assert.assertEquals
import org.junit.Test

class BinanceAccountStateRepositoryTest {

    @Test
    fun `getBalances maps account balances`() {
        kotlinx.coroutines.runBlocking {
            val api = FakeApi()
            val repo = BinanceAccountStateRepository(api)

            val balances: List<BalanceSnapshot> = repo.getBalances()

            assertEquals(2, balances.size)
            assertEquals("USDT", balances[0].asset)
            assertEquals(10.0, balances[0].free, 0.0)
        }
    }

    @Test
    fun `getFills maps trades`() {
        kotlinx.coroutines.runBlocking {
            val api = FakeApi()
            val repo = BinanceAccountStateRepository(api)

            val fills: List<Fill> = repo.getFills("BTCUSDT", sinceMs = 100L)

            assertEquals(1, fills.size)
            assertEquals("BTCUSDT", fills[0].symbol)
            assertEquals(100.0, fills[0].price, 0.0)
        }
    }

    private class FakeApi : BinanceTestNetApiService {
        override suspend fun fetchAccountInfo(): Account {
            return Account(
                balances = listOf(
                    AccountBalance(asset = "USDT", free = 10.0, locked = 1.0),
                    AccountBalance(asset = "BTC", free = 0.5, locked = 0.0)
                )
            )
        }

        override suspend fun getMyTrades(
            symbol: String,
            fromId: Long?,
            startTime: Long?,
            endTime: Long?,
            limit: Int?
        ): List<Trade> {
            return listOf(
                Trade(
                    tradeId = 1L,
                    price = 100.0,
                    quantity = 0.1,
                    timestamp = 123L,
                    isBuyerMaker = true
                )
            )
        }

        override suspend fun createOrder(
            symbol: String,
            side: OrderSide,
            type: OrderType,
            quantity: String,
            price: String?,
            timeInForce: String?
        ): OrderResponse = error("Not used")

        override suspend fun cancelOrder(
            symbol: String,
            orderId: Long?,
            clientOrderId: String?
        ): OrderResponse = error("Not used")

        override suspend fun getOpenOrders(symbol: String?): List<OrderResponse> = emptyList()
    }
}
