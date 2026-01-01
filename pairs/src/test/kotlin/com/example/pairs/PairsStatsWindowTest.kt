package com.example.pairs

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PairsStatsWindowTest {
    @Test
    fun `stats window computes beta and correlation`() {
        val window = PairsStatsWindow(windowMs = 1_000L)
        val now = 1_000L
        val samples = listOf(
            1.0 to 1.0,
            1.1 to 1.1,
            0.9 to 0.9,
            1.2 to 1.2
        )
        samples.forEachIndexed { idx, (a, b) ->
            window.add(now + idx * 10, a, b)
        }
        val beta = window.beta(now + 40)
        val corr = window.correlation(now + 40)
        assertTrue(abs(beta - 1.0) < 0.01)
        assertTrue(abs(corr - 1.0) < 0.01)
    }

    @Test
    fun `spread window tracks mean and trims`() {
        val window = SpreadWindow(windowMs = 100L)
        window.add(0L, 1.0)
        window.add(50L, 3.0)
        assertEquals(2.0, window.mean(50L))
        window.add(200L, 5.0)
        assertEquals(1, window.size(200L))
        assertEquals(5.0, window.mean(200L))
    }

    @Test
    fun `spread window std is zero for constant values`() {
        val window = SpreadWindow(windowMs = 1_000L)
        window.add(0L, 2.0)
        window.add(10L, 2.0)
        window.add(20L, 2.0)
        assertEquals(0.0, window.std(20L))
    }
}
