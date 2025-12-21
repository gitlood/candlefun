package com.example.platformutil

import kotlin.test.Test
import kotlin.test.assertEquals

class PlatformUtilConstantsTest {
    @Test
    fun constants_areStable() {
        assertEquals("ETHUSDT", BINANCE_SYMBOL)
        assertEquals(0.001, BINANCE_FEE)
        assertEquals("active_bots.json", ACTIVE_BOTS_FILE_NAME)
    }
}
