package com.example.survivor

import kotlin.test.Test
import kotlin.test.assertTrue

class SurvivorGateStatsTest {
    @Test
    fun `gate stats report includes counters`() {
        val stats = SurvivorGateStats(
            total = 10,
            rejectedVol = 2,
            rejectedSpread = 1,
            rejectedOi = 3,
            flatFunding = 4,
            entries = 5,
            exits = 6
        )
        val report = stats.report()
        assertTrue(report.contains("total=10"))
        assertTrue(report.contains("vol=2"))
        assertTrue(report.contains("exits=6"))
    }
}
