package com.example.algo

import com.example.algo.priortoprofitgroups.EventStudyAnalyzer
import com.example.platformutil.AlgoConfig
import kotlin.test.Test
import kotlin.test.assertTrue

class EventStudyAnalyzerTest {
    @Test
    fun eventStudyAnalyzer_returnsEmptyWithoutData() {
        assertTrue(EventStudyAnalyzer.lastTopFullKeysByLift(5).isEmpty())
        val result = EventStudyAnalyzer.runAndGetTopFullKeysByLift(
            candles = emptyList(),
            groups = emptyList(),
            cfg = AlgoConfig()
        )
        assertTrue(result.isEmpty())
    }
}
