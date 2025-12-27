package com.example.pairs

import kotlin.test.Test
import kotlin.test.assertTrue

class PairsReportTest {
    @Test
    fun `render includes mode and summary values`() {
        val summary = PairsKpiSummary(
            avgHalfLifeMs = 2_000L,
            tailEvents = 2,
            realizedPnL = 12.5,
            totalFees = 1.25,
            netPnL = 11.25
        )

        val output = PairsReport.render(summary, label = "Pairs Live")

        assertTrue(output.contains("Pairs Live"))
        assertTrue(output.contains("HALF-LIFE"))
        assertTrue(output.contains("mode=live"))
        assertTrue(output.contains("tail_events=2"))
    }
}
