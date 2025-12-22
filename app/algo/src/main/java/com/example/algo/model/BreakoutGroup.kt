package com.example.algo.model

data class BreakoutGroup(
    val entryIndex: Int,
    val entryOpenTime: Long,
    val windowEndOpenTime: Long,

    val entryOpen: Double,
    val entryLow: Double,
    val entryClose: Double,
    val entryVolume: Double,

    val peakIndex: Int,
    val peakOpenTime: Long,
    val peakHigh: Double,
    val peakVolume: Double,

    val minLowInWindow: Double,
    val maxDrawdownPct: Double,        // <= 0, based on entryOpen
    val gainPctToPeakHigh: Double,     // (peakHigh/entryOpen - 1)

    val horizonClose: Double,
    val gainPctToHorizonClose: Double, // (horizonClose/entryOpen - 1)
    val netPct: Double,                // net PnL with TP/SL + costs

    val thresholdHit: Double,          // 0.10 or 0.20 etc.
    val minutesToHit: Int,             // minutes until first hit of thresholdHit
    val minutesToPeak: Int,            // minutes until peakHigh

    val windowVolumeSum: Double,
    val windowTradesSum: Long,
    )
