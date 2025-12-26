package com.example.marketdata.impl.recording

import kotlin.test.Test
import kotlin.test.assertTrue

class MarketStateRecorderConfigTest {
    @Test
    fun `default config sets output path`() {
        val config = MarketStateRecorderConfig.default()
        assertTrue(config.outputPath.isNotBlank())
        assertTrue(config.outputPath.endsWith("marketstate.csv"))
    }
}
