package com.example.execution.domain

import com.example.platform.model.enums.OrderSide
import com.example.platform.model.enums.OrderType
import org.junit.Assert.assertEquals
import org.junit.Test

class ExecutionModelsTest {

    @Test
    fun `execution models preserve values`() {
        val order = ExecutionOrder(
            symbol = "BTCUSDT",
            orderId = 1L,
            clientOrderId = "client",
            price = 100.0,
            originalQty = 2.0,
            executedQty = 1.0,
            status = "NEW",
            type = OrderType.LIMIT,
            side = OrderSide.BUY,
            transactTime = 123L
        )
        val request = OrderRequest(
            symbol = "BTCUSDT",
            side = OrderSide.SELL,
            type = OrderType.MARKET,
            quantity = 0.5,
            price = null,
            timeInForce = null,
            clientOrderId = null
        )
        val cancel = OrderCancelRequest(symbol = "BTCUSDT", orderId = 1L, clientOrderId = null)
        val position = Position(symbol = "BTCUSDT", quantity = 3.0, averagePrice = 99.0)
        val balance = BalanceSnapshot(asset = "USDT", free = 10.0, locked = 1.0)
        val fill = Fill(symbol = "BTCUSDT", price = 100.0, quantity = 0.1, timestamp = 1L, isBuyerMaker = true)

        assertEquals("BTCUSDT", order.symbol)
        assertEquals("BTCUSDT", request.symbol)
        assertEquals(1L, cancel.orderId)
        assertEquals(3.0, position.quantity, 0.0)
        assertEquals("USDT", balance.asset)
        assertEquals(true, fill.isBuyerMaker)
    }

    @Test
    fun `execution credentials hold values`() {
        val creds = ExecutionCredentials(apiKey = "key", secretKey = "secret")
        assertEquals("key", creds.apiKey)
        assertEquals("secret", creds.secretKey)
    }
}
