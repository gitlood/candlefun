package com.example.network.marketstate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotResyncerTest {

    @Test
    fun `tryStart respects in progress and throttle`() {
        val resyncer = SnapshotResyncer(throttleMs = 1_000L)

        assertTrue(resyncer.tryStart("BTCUSDT", 1_000L))
        assertFalse(resyncer.tryStart("BTCUSDT", 1_000L))

        resyncer.markFailure("BTCUSDT")
        assertTrue(resyncer.tryStart("BTCUSDT", 1_500L))

        resyncer.markSuccess("BTCUSDT", 2_000L)
        assertFalse(resyncer.tryStart("BTCUSDT", 2_500L))
        assertTrue(resyncer.tryStart("BTCUSDT", 3_100L))
    }
}
