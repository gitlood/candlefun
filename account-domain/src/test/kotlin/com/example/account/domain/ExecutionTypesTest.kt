package com.example.account.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExecutionTypesTest {
    @Test
    fun `symbol and asset enforce non-blank and uppercase`() {
        assertFailsWith<IllegalArgumentException> { Symbol(" ") }
        assertFailsWith<IllegalArgumentException> { Asset("") }
        assertEquals(Symbol("BTCUSDT"), Symbol.of("btcusdt"))
        assertEquals(Asset("USDT"), Asset.of("usdt"))
        assertEquals("BTCUSDT", Symbol.of("btcusdt").toString())
    }

    @Test
    fun `price and qty helpers work`() {
        val price = Price.fromString("123.45")
        val qty = Qty.fromDouble(2.0)

        assertTrue(price > Price.fromDouble(100.0))
        assertEquals("123.45", price.toString())
        assertEquals(Qty.fromDouble(3.0), qty + Qty.fromDouble(1.0))
        assertEquals(Qty.fromDouble(1.0), qty - Qty.fromDouble(1.0))
        assertEquals(Qty.fromDouble(-2.0), -qty)
        assertTrue(Qty.ZERO.isZero())
        assertEquals(2.0, qty.toDouble())
    }

    @Test
    fun `money helpers work`() {
        val a = Money.fromDouble(10.0)
        val b = Money.fromString("1.5")
        assertEquals("11.5", (a + b).toString())
        assertEquals("8.5", (a - b).toString())
    }
}
