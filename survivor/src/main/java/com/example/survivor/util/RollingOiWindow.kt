package com.example.survivor.util

import kotlin.math.abs

class RollingOiWindow(private val windowMs: Long) {
    private val samples = ArrayDeque<TimedOi>(64)
    private var sum = 0.0

    fun add(timestampMs: Long, oi: Double) {
        samples.addLast(TimedOi(timestampMs, oi))
        sum += oi
        trim(timestampMs)
    }

    fun isJumping(current: Double, maxJumpPct: Double): Boolean {
        if (samples.isEmpty()) return false
        val mean = sum / samples.size
        if (mean <= 0.0) return false
        val pct = abs(current - mean) / mean
        return pct > maxJumpPct
    }

    private fun trim(nowMs: Long) {
        while (samples.isNotEmpty() && samples.first().timestampMs < nowMs - windowMs) {
            val s = samples.removeFirst()
            sum -= s.oi
        }
    }

    private data class TimedOi(val timestampMs: Long, val oi: Double)
}
