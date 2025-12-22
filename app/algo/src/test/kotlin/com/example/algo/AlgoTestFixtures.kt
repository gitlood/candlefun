package com.example.algo

import com.example.algo.model.BreakoutGroup
import com.example.platformutil.model.Candle

internal fun candle(
    openTime: Long,
    open: Double = 1.0,
    high: Double = 1.0,
    low: Double = 1.0,
    close: Double = 1.0
): Candle {
    return Candle(
        openTime = openTime,
        open = open.toString(),
        high = high.toString(),
        low = low.toString(),
        close = close.toString(),
        volume = "10",
        closeTime = openTime + 60_000L,
        numberOfTrades = 1
    )
}

internal fun breakoutGroup(entryOpenTime: Long, windowEnd: Long, gain: Double): BreakoutGroup {
    return BreakoutGroup(
        entryIndex = 0,
        entryOpenTime = entryOpenTime,
        windowEndOpenTime = windowEnd,
        entryOpen = 1.0,
        entryLow = 1.0,
        entryClose = 1.0,
        entryVolume = 10.0,
        peakIndex = 1,
        peakOpenTime = windowEnd,
        peakHigh = 1.0 + gain,
        peakVolume = 10.0,
        minLowInWindow = 1.0,
        maxDrawdownPct = -0.01,
        gainPctToPeakHigh = gain,
        horizonClose = 1.0,
        gainPctToHorizonClose = gain,
        netPct = gain,
        thresholdHit = 0.05,
        minutesToHit = 1,
        minutesToPeak = 1,
        windowVolumeSum = 20.0,
        windowTradesSum = 2
    )
}
