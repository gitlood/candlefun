package com.example.algo.model

data class BacktestFeatures(
    val trendSlope: Double,
    val volStd: Double,
    val contraction10vLookback: Double,
    val rangeMean: Double,
    val volumeZ: Double,
    val ret5m: Double,
    val ret15m: Double,
    val ret30m: Double,
    val orderBookImbalance10: Double? = null,
    val orderBookSpreadBps: Double? = null,
    val orderBookAvailable: Boolean = false
)
