package com.example.network.marketstate

import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

internal class SnapshotResyncer(
    private val throttle: Duration
) {
    private val states = ConcurrentHashMap<String, ResyncState>()

    fun tryStart(symbol: String, nowMs: Long): Boolean {
        val state = states.computeIfAbsent(symbol) { ResyncState() }
        synchronized(state) {
            if (state.inProgress) return false
            if (nowMs - state.lastSnapshotAt < throttle.inWholeMilliseconds) return false
            state.inProgress = true
            return true
        }
    }

    fun markSuccess(symbol: String, nowMs: Long) {
        val state = states.computeIfAbsent(symbol) { ResyncState() }
        synchronized(state) {
            state.lastSnapshotAt = nowMs
            state.inProgress = false
        }
    }

    fun markFailure(symbol: String) {
        val state = states.computeIfAbsent(symbol) { ResyncState() }
        synchronized(state) {
            state.inProgress = false
        }
    }

    private class ResyncState {
        var lastSnapshotAt: Long = 0L
        var inProgress: Boolean = false
    }
}
