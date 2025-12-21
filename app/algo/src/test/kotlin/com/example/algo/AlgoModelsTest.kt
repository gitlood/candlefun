package com.example.algo

import com.example.algo.model.BacktestFeatures
import com.example.algo.model.ReportParams
import kotlin.test.Test
import kotlin.test.assertEquals

class AlgoModelsTest {
    @Test
    fun modelEquality_usesArrayContent() {
        val paramsA = ReportParams(
            horizonMinutes = 10,
            horizonBars = 2,
            thresholdsPct = doubleArrayOf(0.05, 0.03),
            localLowLookBackMinutes = 0,
            lookBackBars = 0,
            requireContinuous = true,
            dedupeOverlappingWindows = true,
            sortBy = "GAIN",
            intervalMillis = 60_000L,
            maxGroupsToPrint = 1,
            maxDrawdownPctAllowed = 0.2
        )
        val paramsB = paramsA.copy(thresholdsPct = doubleArrayOf(0.05, 0.03))
        assertEquals(paramsA, paramsB)

        val features = BacktestFeatures(
            trendSlope = 0.1,
            volStd = 0.2,
            contraction10vLookback = 0.3,
            rangeMean = 0.4,
            volumeZ = 1.0,
            ret5m = 0.01,
            ret15m = 0.02,
            ret30m = 0.03
        )
        assertEquals(0.1, features.trendSlope)
    }
}
