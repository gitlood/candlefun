package com.example.network.candlecollector.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IntervalsTest {

    @Test
    fun `toMs converts minutes correctly`() {
        assertEquals(60_000L, Intervals.toMs("1m"))
        assertEquals(300_000L, Intervals.toMs("5m"))
        assertEquals(900_000L, Intervals.toMs("15m"))
    }

    @Test
    fun `toMs converts hours correctly`() {
        assertEquals(3_600_000L, Intervals.toMs("1h"))
        assertEquals(14_400_000L, Intervals.toMs("4h"))
    }

    @Test
    fun `toMs converts days correctly`() {
        assertEquals(86_400_000L, Intervals.toMs("1d"))
        assertEquals(259_200_000L, Intervals.toMs("3d"))
    }

    @Test
    fun `toMs converts weeks correctly`() {
        assertEquals(604_800_000L, Intervals.toMs("1w"))
    }

    @Test
    fun `toMs rejects months due to variable length`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            Intervals.toMs("1M")
        }
        assertEquals("Monthly intervals ('M') not supported for ms stepping due to variable length", ex.message)
    }

    @Test
    fun `toMs rejects invalid formats`() {
        assertThrows(IllegalArgumentException::class.java) {
            Intervals.toMs("invalid")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Intervals.toMs("m")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Intervals.toMs("")
        }
    }
}
