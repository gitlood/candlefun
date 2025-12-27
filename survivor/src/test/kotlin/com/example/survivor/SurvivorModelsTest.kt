package com.example.survivor

import kotlin.test.Test
import kotlin.test.assertEquals

class SurvivorModelsTest {
    @Test
    fun `basis pct uses index price`() {
        val snapshot = SurvivorSnapshot(
            symbol = "BTCUSDT",
            timestampMs = 1L,
            fundingRate = 0.0,
            nextFundingTimeMs = 2L,
            markPrice = 101.0,
            indexPrice = 100.0,
            spreadPct = 0.0,
            volatility = 0.0,
            openInterest = 0.0
        )
        assertEquals(0.01, snapshot.basisPct)
    }
}
