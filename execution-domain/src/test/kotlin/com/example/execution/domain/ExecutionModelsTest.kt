package com.example.execution.domain

import com.example.account.domain.Price
import com.example.account.domain.Qty
import com.example.account.domain.Symbol
import com.example.platform.model.enums.OrderSide
import com.example.platform.model.enums.OrderType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExecutionModelsTest {
    @Test
    fun `order request defaults are preserved`() {
        val request = OrderRequest(
            symbol = Symbol.of("btcusdt"),
            side = OrderSide.BUY,
            type = OrderType.LIMIT,
            quantity = Qty.fromDouble(0.01),
            price = Price.fromDouble(100.0)
        )
        assertNull(request.timeInForce)
        assertNull(request.clientOrderId)
    }

    @Test
    fun `execution order stores values`() {
        val order = ExecutionOrder(
            symbol = Symbol.of("ethusdt"),
            orderId = 1L,
            clientOrderId = "cid",
            price = Price.fromDouble(2000.0),
            originalQty = Qty.fromDouble(1.0),
            executedQty = Qty.ZERO,
            status = OrderStatus.NEW,
            type = OrderType.LIMIT,
            side = OrderSide.SELL,
            timeInForce = TimeInForce.GTC,
            transactTimeMs = 123L
        )
        assertEquals(1L, order.orderId)
        assertEquals("cid", order.clientOrderId)
        assertEquals(TimeInForce.GTC, order.timeInForce)
    }
}
