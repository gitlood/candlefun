package com.example.ofi.kukanov.util

class RollingAverageWindow(private val windowMs: Long) {
    private val values = ArrayDeque<TimedSample>()
    private var sum = 0.0

    fun add(timestampMs: Long, value: Double) {
        values.addLast(TimedSample(timestampMs, value))
        sum += value
        trim(timestampMs)
    }

    fun mean(nowMs: Long): Double? {
        trim(nowMs)
        return if (values.isEmpty()) null else sum / values.size
    }

    fun count(nowMs: Long): Int {
        trim(nowMs)
        return values.size
    }

    private fun trim(nowMs: Long) {
        while (values.isNotEmpty() && values.first().timestampMs < nowMs - windowMs) {
            val removed = values.removeFirst()
            sum -= removed.value
        }
    }

    private data class TimedSample(val timestampMs: Long, val value: Double)
}
