package com.example.network.marketstate

import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RollingStatsTest {

    @Test
    fun `rolling sum trims values outside window`() {
        val window = RollingSumWindow(1_000.milliseconds)
        window.add(0L, 1.0)
        window.add(500L, 2.0)
        window.add(1_500L, 3.0)

        assertEquals(5.0, window.current(1_500L), 0.0001)
        assertEquals(0.0, window.current(2_600L), 0.0001)
    }

    @Test
    fun `rolling trade window tracks volumes`() {
        val window = RollingTradeWindow(1_000.milliseconds)
        window.add(0L, 1.0, isBuyerMaker = false)
        window.add(500L, 2.0, isBuyerMaker = true)
        window.add(1_500L, 3.0, isBuyerMaker = false)

        val snapshot = window.snapshot(1_500L)

        assertEquals(2, snapshot.count)
        assertEquals(5.0, snapshot.volume, 0.0001)
        assertEquals(3.0, snapshot.buyVolume, 0.0001)
        assertEquals(2.0, snapshot.sellVolume, 0.0001)
    }

    @Test
    fun `rolling volatility returns sigma when enough data`() {
        val vol = RollingVolatility(listOf(1_000.milliseconds))
        vol.addPrice(0L, 100.0)
        vol.addPrice(500L, 101.0)
        vol.addPrice(900L, 99.0)

        val sigma = vol.sigma(1_000.milliseconds, 900L)

        val r1 = ln(101.0 / 100.0)
        val r2 = ln(99.0 / 101.0)
        val expected = sqrt((r1 * r1 + r2 * r2) / 2.0)
        assertNotNull(sigma)
        assertEquals(expected, sigma!!, 1e-9)
    }

    @Test
    fun `rolling volatility returns null when insufficient data`() {
        val vol = RollingVolatility(listOf(1_000.milliseconds))
        vol.addPrice(0L, 100.0)

        assertNull(vol.sigma(1_000.milliseconds, 0L))
    }
}
