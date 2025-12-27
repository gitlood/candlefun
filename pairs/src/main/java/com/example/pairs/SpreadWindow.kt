package com.example.pairs

import kotlin.math.sqrt

class SpreadWindow(private val windowMs: Long) {
    private val samples = ArrayDeque<TimedValue>(256)
    private var sum = 0.0
    private var sumSq = 0.0

    fun add(timestampMs: Long, spread: Double) {
        samples.addLast(TimedValue(timestampMs, spread))
        sum += spread
        sumSq += spread * spread
        trim(timestampMs)
    }

    fun mean(timestampMs: Long): Double? {
        trim(timestampMs)
        if (samples.isEmpty()) return null
        return sum / samples.size
    }

    fun std(timestampMs: Long): Double? {
        trim(timestampMs)
        val n = samples.size
        if (n < 2) return null
        val mean = sum / n
        val variance = (sumSq / n) - mean * mean
        return if (variance > 0.0) sqrt(variance) else 0.0
    }

    fun size(timestampMs: Long): Int {
        trim(timestampMs)
        return samples.size
    }

    private fun trim(nowMs: Long) {
        while (samples.isNotEmpty() && samples.first().timestampMs < nowMs - windowMs) {
            val s = samples.removeFirst()
            sum -= s.value
            sumSq -= s.value * s.value
        }
    }

    private data class TimedValue(val timestampMs: Long, val value: Double)
}
