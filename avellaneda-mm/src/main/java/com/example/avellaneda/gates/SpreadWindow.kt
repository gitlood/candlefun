package com.example.avellaneda.gates

class SpreadWindow(
    private val windowMs: Long,
    private val capacity: Int = 256
) {
    private val samples = ArrayDeque<Sample>(capacity)

    fun add(timestampMs: Long, value: Double) {
        samples.addLast(Sample(timestampMs, value))
        if (samples.size > capacity) {
            samples.removeFirst()
        }
        trim(timestampMs)
    }

    fun average(timestampMs: Long): Double? {
        trim(timestampMs)
        if (samples.isEmpty()) return null
        var sum = 0.0
        for (s in samples) sum += s.value
        return sum / samples.size
    }

    private fun trim(nowMs: Long) {
        val cutoff = nowMs - windowMs
        while (samples.isNotEmpty() && samples.first().timestampMs < cutoff) {
            samples.removeFirst()
        }
    }

    private data class Sample(val timestampMs: Long, val value: Double)
}
