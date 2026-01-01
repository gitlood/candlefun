package com.example.execution.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class ExecutionEnumsTest {
    @Test
    fun `order status fromString handles null and invalid`() {
        assertEquals(OrderStatus.UNKNOWN, OrderStatus.fromString(null))
        assertEquals(OrderStatus.UNKNOWN, OrderStatus.fromString(" "))
        assertEquals(OrderStatus.UNKNOWN, OrderStatus.fromString("not-a-status"))
        assertEquals(OrderStatus.FILLED, OrderStatus.fromString("filled"))
    }

    @Test
    fun `time in force fromString handles null and invalid`() {
        assertEquals(TimeInForce.UNKNOWN, TimeInForce.fromString(null))
        assertEquals(TimeInForce.UNKNOWN, TimeInForce.fromString(" "))
        assertEquals(TimeInForce.UNKNOWN, TimeInForce.fromString("nope"))
        assertEquals(TimeInForce.GTC, TimeInForce.fromString("gtc"))
    }
}
