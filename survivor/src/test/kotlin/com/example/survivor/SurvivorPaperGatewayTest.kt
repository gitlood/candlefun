package com.example.survivor

import com.example.account.domain.Price
import com.example.account.domain.Qty
import com.example.account.domain.Symbol
import com.example.execution.domain.OrderRequest
import com.example.execution.domain.TimeInForce
import com.example.platform.model.enums.OrderSide
import com.example.platform.model.enums.OrderType
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SurvivorPaperGatewayTest {
    @Test
    fun `place order updates position and notifies fills`() = runBlocking {
        val fills = mutableListOf<SurvivorFill>()
        val gateway = SurvivorPaperGateway { fills.add(it) }
        val symbol = Symbol.of("BTCUSDT")

        gateway.placeOrder(
            OrderRequest(
                symbol = symbol,
                side = OrderSide.BUY,
                type = OrderType.LIMIT,
                quantity = Qty.fromDouble(1.0),
                price = Price.fromDouble(100.0),
                timeInForce = TimeInForce.GTC,
                clientOrderId = "buy1"
            )
        )
        gateway.placeOrder(
            OrderRequest(
                symbol = symbol,
                side = OrderSide.BUY,
                type = OrderType.LIMIT,
                quantity = Qty.fromDouble(1.0),
                price = Price.fromDouble(200.0),
                timeInForce = TimeInForce.GTC,
                clientOrderId = "buy2"
            )
        )

        val positions = gateway.getPositions()
        assertEquals(1, positions.size)
        val pos = positions.first()
        assertEquals(2.0, pos.quantity.toDouble(), 1e-9)
        assertEquals(150.0, pos.averagePrice.value.toDouble(), 1e-9)
        assertEquals(2, fills.size)
        assertTrue(fills.all { it.symbol == symbol })
    }

    @Test
    fun `sell to flat resets average price`() = runBlocking {
        val gateway = SurvivorPaperGateway()
        val symbol = Symbol.of("BTCUSDT")

        gateway.placeOrder(
            OrderRequest(
                symbol = symbol,
                side = OrderSide.BUY,
                type = OrderType.LIMIT,
                quantity = Qty.fromDouble(1.0),
                price = Price.fromDouble(100.0),
                timeInForce = TimeInForce.GTC,
                clientOrderId = "buy"
            )
        )
        gateway.placeOrder(
            OrderRequest(
                symbol = symbol,
                side = OrderSide.SELL,
                type = OrderType.LIMIT,
                quantity = Qty.fromDouble(1.0),
                price = Price.fromDouble(110.0),
                timeInForce = TimeInForce.GTC,
                clientOrderId = "sell"
            )
        )

        val pos = gateway.getPositions().first()
        assertEquals(0.0, pos.quantity.toDouble(), 1e-9)
        assertEquals(Price.ZERO, pos.averagePrice)
    }
}
