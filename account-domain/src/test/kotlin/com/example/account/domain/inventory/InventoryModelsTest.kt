package com.example.account.domain.inventory

import com.example.account.domain.Money
import com.example.account.domain.Price
import com.example.account.domain.Qty
import com.example.account.domain.Symbol
import kotlin.test.Test
import kotlin.test.assertEquals

class InventoryModelsTest {
    @Test
    fun `inventory models retain values`() {
        val symbol = Symbol.of("ethusdt")
        val position = InventoryPosition(
            symbol = symbol,
            quantity = Qty.fromDouble(1.25),
            avgPrice = Price.fromDouble(2500.0),
            realizedPnl = Money.fromDouble(12.5),
            unrealizedPnl = Money.ZERO,
            free = Qty.fromDouble(1.0),
            locked = Qty.fromDouble(0.25)
        )
        val fill = InventoryFill(
            symbol = symbol,
            signedQty = Qty.fromDouble(-0.5),
            price = Price.fromDouble(2600.0),
            timestampMs = 1234L
        )

        assertEquals(symbol, position.symbol)
        assertEquals(Qty.fromDouble(-0.5), fill.signedQty)
        assertEquals(Money.ZERO, fill.fee)
    }
}
