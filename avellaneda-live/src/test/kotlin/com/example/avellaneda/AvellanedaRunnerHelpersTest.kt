package com.example.avellaneda

import kotlin.test.Test
import kotlin.test.assertEquals

class AvellanedaRunnerHelpersTest {
    @Test
    fun `testnet runner parses quote style and formats to step`() {
        val styleMethod = AvellanedaMmTestnetRunner::class.java
            .getDeclaredMethod("parseQuoteStyle", String::class.java)
            .apply { isAccessible = true }
        assertEquals(QuoteStyle.JOIN, styleMethod.invoke(AvellanedaMmTestnetRunner, "bad"))
        assertEquals(QuoteStyle.IMPROVE, styleMethod.invoke(AvellanedaMmTestnetRunner, "IMPROVE"))

        val formatMethod = AvellanedaMmTestnetRunner::class.java
            .getDeclaredMethod("formatToStep", Double::class.java, Double::class.java)
            .apply { isAccessible = true }
        assertEquals("1.23", formatMethod.invoke(AvellanedaMmTestnetRunner, 1.234, 0.01))
        assertEquals("2.0", formatMethod.invoke(AvellanedaMmTestnetRunner, 2.0, 0.0))
    }
}
