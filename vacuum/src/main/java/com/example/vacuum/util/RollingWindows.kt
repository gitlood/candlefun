package com.example.vacuum.util

import com.example.platform.model.BookLevel
import kotlin.math.max

class RollingMaxWindow(private val windowMs: Long) {
    private val values = ArrayDeque<TimedValue>()

    fun add(timestampMs: Long, value: Double) {
        values.addLast(TimedValue(timestampMs, value))
        trim(timestampMs)
    }

    fun max(timestampMs: Long): Double? {
        trim(timestampMs)
        var m = Double.NEGATIVE_INFINITY
        for (v in values) if (v.value > m) m = v.value
        return if (m == Double.NEGATIVE_INFINITY) null else m
    }

    private fun trim(nowMs: Long) {
        while (values.isNotEmpty() && values.first().timestampMs < nowMs - windowMs) {
            values.removeFirst()
        }
    }

    private data class TimedValue(val timestampMs: Long, val value: Double)
}

class RollingAverageWindow(private val windowMs: Long) {
    private val values = ArrayDeque<TimedValue>()
    private var sum = 0.0

    fun add(timestampMs: Long, value: Double) {
        values.addLast(TimedValue(timestampMs, value))
        sum += value
        trim(timestampMs)
    }

    fun mean(timestampMs: Long): Double? {
        trim(timestampMs)
        return if (values.isEmpty()) null else sum / values.size
    }

    private fun trim(nowMs: Long) {
        while (values.isNotEmpty() && values.first().timestampMs < nowMs - windowMs) {
            val removed = values.removeFirst()
            sum -= removed.value
        }
    }

    private data class TimedValue(val timestampMs: Long, val value: Double)
}
