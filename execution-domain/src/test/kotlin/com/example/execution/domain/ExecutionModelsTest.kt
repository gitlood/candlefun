package com.example.execution.domain

import com.example.account.domain.Asset
import com.example.account.domain.BalanceSnapshot
import com.example.account.domain.Fill
import com.example.account.domain.Position
import com.example.account.domain.Price
import com.example.account.domain.Qty
import com.example.account.domain.Symbol
import com.example.platform.model.enums.OrderSide
import com.example.platform.model.enums.OrderType
import org.junit.Assert.assertEquals
import org.junit.Test

class ExecutionModelsTest {

    @Test
    fun `execution models preserve values`() {
        val order = ExecutionOrder(
            symbol = Symbol.of("BTCUSDT"),
            orderId = 1L,
            clientOrderId = "client",
            price = Price.fromDouble(100.0),
            originalQty = Qty.fromDouble(2.0),
            executedQty = Qty.fromDouble(1.0),
            status = OrderStatus.NEW,
            type = OrderType.LIMIT,
            side = OrderSide.BUY,
            timeInForce = TimeInForce.GTC,
            transactTimeMs = 123L
        )
        val request = OrderRequest(
            symbol = Symbol.of("BTCUSDT"),
            side = OrderSide.SELL,
            type = OrderType.MARKET,
            quantity = Qty.fromDouble(0.5),
            price = null,
            timeInForce = null,
            clientOrderId = null
        )
        val cancel = OrderCancelRequest(symbol = Symbol.of("BTCUSDT"), orderId = 1L, clientOrderId = null)
        val position = Position(
            symbol = Symbol.of("BTCUSDT"),
            quantity = Qty.fromDouble(3.0),
            averagePrice = Price.fromDouble(99.0)
        )
        val balance = BalanceSnapshot(
            asset = Asset.of("USDT"),
            free = Qty.fromDouble(10.0),
            locked = Qty.fromDouble(1.0)
        )
        val fill = Fill(
            symbol = Symbol.of("BTCUSDT"),
            price = Price.fromDouble(100.0),
            quantity = Qty.fromDouble(0.1),
            fillTimeMs = 1L,
            isBuyerMaker = true
        )

        assertEquals("BTCUSDT", order.symbol.value)
        assertEquals("BTCUSDT", request.symbol.value)
        assertEquals(1L, cancel.orderId)
        assertEquals(3.0, position.quantity.toDouble(), 0.0)
        assertEquals("USDT", balance.asset.value)
        assertEquals(true, fill.isBuyerMaker)
    }

    @Test
    fun `execution credentials hold values`() {
        val creds = ExecutionCredentials(apiKey = "key", secretKey = "secret")
        assertEquals("key", creds.apiKey)
        assertEquals("secret", creds.secretKey)
    }
}
