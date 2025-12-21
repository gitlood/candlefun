package com.example.algo

import com.example.algo.profitgroups.ProfitGroupQualityGate
import com.example.platformutil.AlgoConfig
import com.example.platformutil.EventStudyConfig
import com.example.platformutil.ProfitGroupConfig
import kotlin.test.Test
import kotlin.test.assertTrue

class ProfitGroupQualityGateTest {
    @Test
    fun profitGroupQualityGate_rejectsEmptyAndAcceptsHealthy() {
        val cfg = AlgoConfig(
            profitGroup = ProfitGroupConfig(thresholds = doubleArrayOf(0.05), maxDrawdownAllowed = 0.2),
            eventStudy = EventStudyConfig(topK = 1, minPosCount = 1)
        )
        val emptyDecision = ProfitGroupQualityGate.decide(cfg, emptyList())
        assertTrue(!emptyDecision.proceed)

        val groups = listOf(breakoutGroup(entryOpenTime = 1L, windowEnd = 2L, gain = 0.1))
        val okDecision = ProfitGroupQualityGate.decide(cfg, groups)
        assertTrue(okDecision.proceed)
    }

}
