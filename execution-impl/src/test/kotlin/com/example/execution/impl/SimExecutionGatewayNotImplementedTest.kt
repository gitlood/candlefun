package com.example.execution.impl

import com.example.execution.domain.AccountStateRepository
import com.example.execution.domain.BalanceSnapshot
import com.example.execution.domain.Fill
import com.example.execution.domain.OrderCancelRequest
import com.example.execution.domain.OrderRequest
import com.example.platform.model.enums.OrderSide
import com.example.platform.model.enums.OrderType
import org.junit.Assert.assertTrue
import org.junit.Test

class SimExecutionGatewayNotImplementedTest {

    @Test
    fun `unimplemented methods throw`() {
        kotlinx.coroutines.runBlocking {
            val repo = object : AccountStateRepository {
                override suspend fun getBalances(): List<BalanceSnapshot> = emptyList()
                override suspend fun getFills(symbol: String, sinceMs: Long?): List<Fill> = emptyList()
            }
            val gateway = SimExecutionGateway(repo)

            assertTrue(throwsNotImplemented { gateway.placeOrder(orderRequest()) })
            assertTrue(throwsNotImplemented { gateway.cancelOrder(OrderCancelRequest("BTCUSDT", 1L)) })
            assertTrue(
                throwsNotImplemented {
                    gateway.replaceOrder(
                        OrderCancelRequest("BTCUSDT", 1L),
                        orderRequest()
                    )
                }
            )
            assertTrue(throwsNotImplemented { gateway.getOpenOrders("BTCUSDT") })
        }
    }

    @Test
    fun `sim account state repository throws`() {
        kotlinx.coroutines.runBlocking {
            val repo = SimAccountStateRepository()
            assertTrue(throwsNotImplemented { repo.getBalances() })
            assertTrue(throwsNotImplemented { repo.getFills("BTCUSDT", sinceMs = null) })
        }
    }

    private fun orderRequest(): OrderRequest {
        return OrderRequest(
            symbol = "BTCUSDT",
            side = OrderSide.BUY,
            type = OrderType.MARKET,
            quantity = 1.0
        )
    }

    private inline fun throwsNotImplemented(block: () -> Unit): Boolean {
        return try {
            block()
            false
        } catch (_: NotImplementedError) {
            true
        }
    }
}
