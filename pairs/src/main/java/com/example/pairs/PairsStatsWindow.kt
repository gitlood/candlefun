package com.example.pairs

import kotlin.math.sqrt

class PairsStatsWindow(private val windowMs: Long) {
    private val samples = ArrayDeque<Sample>(256)
    private var sumA = 0.0
    private var sumB = 0.0
    private var sumA2 = 0.0
    private var sumB2 = 0.0
    private var sumAB = 0.0

    fun add(timestampMs: Long, logA: Double, logB: Double) {
        val sample = Sample(timestampMs, logA, logB)
        samples.addLast(sample)
        sumA += logA
        sumB += logB
        sumA2 += logA * logA
        sumB2 += logB * logB
        sumAB += logA * logB
        trim(timestampMs)
    }

    fun size(timestampMs: Long): Int {
        trim(timestampMs)
        return samples.size
    }

    fun beta(timestampMs: Long): Double {
        trim(timestampMs)
        val n = samples.size
        if (n < 2) return 1.0
        val meanA = sumA / n
        val meanB = sumB / n
        val cov = sumAB / n - meanA * meanB
        val varB = sumB2 / n - meanB * meanB
        return if (varB > 0.0) cov / varB else 1.0
    }

    fun correlation(timestampMs: Long): Double {
        trim(timestampMs)
        val n = samples.size
        if (n < 2) return 0.0
        val meanA = sumA / n
        val meanB = sumB / n
        val cov = sumAB / n - meanA * meanB
        val varA = sumA2 / n - meanA * meanA
        val varB = sumB2 / n - meanB * meanB
        if (varA <= 0.0 || varB <= 0.0) return 0.0
        return cov / sqrt(varA * varB)
    }

    private fun trim(nowMs: Long) {
        while (samples.isNotEmpty() && samples.first().timestampMs < nowMs - windowMs) {
            val s = samples.removeFirst()
            sumA -= s.logA
            sumB -= s.logB
            sumA2 -= s.logA * s.logA
            sumB2 -= s.logB * s.logB
            sumAB -= s.logA * s.logB
        }
    }

    private data class Sample(
        val timestampMs: Long,
        val logA: Double,
        val logB: Double
    )
}
