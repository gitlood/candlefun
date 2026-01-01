package com.example.execution.impl.di

import com.example.account.domain.AccountStateRepository
import com.example.account.impl.di.accountImplModule
import com.example.execution.domain.ExecutionCredentials
import com.example.execution.domain.ExecutionCredentialsProvider
import com.example.execution.domain.ExecutionGateway
import com.example.network.interfaces.BinanceTestNetApiService
import com.example.platform.model.Account
import com.example.platform.model.OrderResponse
import com.example.platform.model.Trade
import com.example.platform.model.enums.OrderSide
import com.example.platform.model.enums.OrderType
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

class ExecutionImplModuleTest : KoinComponent {

    @Test
    fun `module provides gateway and repositories`() {
        val overrides = module {
            single<ExecutionCredentialsProvider> {
                object : ExecutionCredentialsProvider {
                    override fun testnet(): ExecutionCredentials = ExecutionCredentials("k", "s")
                }
            }
            factory<BinanceTestNetApiService> { (_: String, _: String) ->
                object : BinanceTestNetApiService {
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

                    override suspend fun getOpenOrders(symbol: String?) = emptyList<OrderResponse>()

                    override suspend fun getMyTrades(
                        symbol: String,
                        fromId: Long?,
                        startTime: Long?,
                        endTime: Long?,
                        limit: Int?
                    ): List<Trade> = emptyList()

                    override suspend fun fetchAccountInfo(): Account = Account()
                }
            }
        }

        startKoin {
            allowOverride(true)
            modules(accountImplModule, executionImplModule, overrides)
        }
        try {
            assertNotNull(get<ExecutionGateway>())
            assertNotNull(get<AccountStateRepository>())
            assertNotNull(get<BinanceTestNetApiService>())
            assertNotNull(get<com.example.execution.impl.SimAccountStateRepository>())
            assertNotNull(get<com.example.execution.impl.SimExecutionGateway>())
        } finally {
            stopKoin()
        }
    }
}
