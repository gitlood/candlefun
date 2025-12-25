package com.example.marketdata.model

data class MarketStateConfig(
    val tickMs: Long = 200L,
    val depthLevels: Int = 10,
    val depthSpeedMs: Int = 100,
    val snapshotDepthLimit: Int = 100,
    val snapshotThrottleMs: Long = 1_000L,
    val ofiWindowMs: Long = 1_000L,
    val tradeWindowMs: Long = 1_000L,
    val volWindowsMs: List<Long> = listOf(1_000L, 5_000L, 10_000L, 60_000L, 300_000L)
)
