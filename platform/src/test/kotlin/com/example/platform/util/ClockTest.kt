package com.example.platform.util

import kotlin.test.Test
import kotlin.test.assertTrue

class ClockTest {
    @Test
    fun `system clock returns non-zero time`() {
        val now = SystemClock.nowMs()
        assertTrue(now > 0)
    }
}
