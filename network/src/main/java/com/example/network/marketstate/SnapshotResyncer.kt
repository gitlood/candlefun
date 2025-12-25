package com.example.network.marketstate

import java.util.concurrent.ConcurrentHashMap

internal class SnapshotResyncer(
    private val throttleMs: Long
) {
    private val states = ConcurrentHashMap<String, ResyncState>()

    fun tryStart(symbol: String, nowMs: Long): Boolean {
        val state = states.computeIfAbsent(symbol) { ResyncState() }
        synchronized(state) {
            if (state.inProgress) return false
            if (nowMs - state.lastSnapshotAt < throttleMs) return false
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
