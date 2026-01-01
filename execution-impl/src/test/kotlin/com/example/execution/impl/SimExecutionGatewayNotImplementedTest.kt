package com.example.execution.impl

import com.example.execution.domain.OrderCancelRequest
import com.example.execution.domain.OrderRequest
import com.example.execution.domain.OrderStatus
import com.example.execution.domain.TimeInForce
import com.example.account.domain.Price
import com.example.account.domain.Qty
import com.example.account.domain.Symbol
import com.example.account.domain.Fill
import com.example.platform.model.enums.OrderSide
import com.example.platform.model.enums.OrderType
import org.junit.Assert.assertEquals
import org.junit.Test

class SimExecutionGatewayNotImplementedTest {

    @Test
    fun `place cancel and replace orders`() {
        kotlinx.coroutines.runBlocking {
            val repo = SimAccountStateRepository()
            val gateway = SimExecutionGateway(repo)

            val placed = gateway.placeOrder(orderRequest())
            assertEquals(OrderStatus.NEW, placed.status)

            val canceled = gateway.cancelOrder(OrderCancelRequest(Symbol.of("BTCUSDT"), placed.orderId))
            assertEquals(OrderStatus.CANCELED, canceled.status)

            val replaced = gateway.replaceOrder(
                OrderCancelRequest(Symbol.of("BTCUSDT"), placed.orderId),
                orderRequest()
            )
            assertEquals(OrderStatus.NEW, replaced.status)
        }
    }

    @Test
    fun `sim account state repository tracks fills`() {
        kotlinx.coroutines.runBlocking {
            val repo = SimAccountStateRepository()
            repo.recordFill(
                Fill(
                    symbol = Symbol.of("BTCUSDT"),
                    price = Price.fromDouble(1.0),
                    quantity = Qty.fromDouble(2.0),
                    fillTimeMs = 1000L,
                    isBuyerMaker = true
                )
            )
            val fills = repo.getFills(Symbol.of("BTCUSDT"), sinceTimeMs = null)
            assertEquals(1, fills.size)
        }
    }

    private fun orderRequest(): OrderRequest {
        return OrderRequest(
            symbol = Symbol.of("BTCUSDT"),
            side = OrderSide.BUY,
            type = OrderType.LIMIT,
            quantity = Qty.fromDouble(1.0),
            price = Price.fromDouble(100.0),
            timeInForce = TimeInForce.GTC
        )
    }
}
