package com.example.marketdata.model

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

data class MarketStateConfig(
    val tick: Duration = 200.milliseconds,
    val depthLevels: Int = 10,
    val depthSpeed: Duration = 100.milliseconds,
    val snapshotDepthLimit: Int = 100,
    val snapshotThrottle: Duration = 1.seconds,
    val ofiWindow: Duration = 1.seconds,
    val tradeWindow: Duration = 1.seconds,
    val volWindows: List<Duration> = listOf(1.seconds, 5.seconds, 10.seconds, 1.minutes, 5.minutes)
) {
    init {
        require(tick > Duration.ZERO) { "tick must be > 0" }
        require(depthLevels > 0) { "depthLevels must be > 0" }
        require(depthSpeed > Duration.ZERO) { "depthSpeed must be > 0" }
        require(snapshotDepthLimit >= depthLevels) { "snapshotDepthLimit must be >= depthLevels" }
        require(snapshotThrottle > Duration.ZERO) { "snapshotThrottle must be > 0" }
        require(ofiWindow > Duration.ZERO) { "ofiWindow must be > 0" }
        require(tradeWindow > Duration.ZERO) { "tradeWindow must be > 0" }
        require(volWindows.isNotEmpty()) { "volWindows must not be empty" }
        require(volWindows.all { it > Duration.ZERO }) { "volWindows must all be > 0" }
    }
}
