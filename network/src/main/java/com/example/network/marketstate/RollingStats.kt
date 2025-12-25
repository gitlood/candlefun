package com.example.network.marketstate

import java.util.ArrayDeque
import kotlin.math.ln
import kotlin.math.sqrt

internal class RollingSumWindow(private val windowMs: Long) {
    private val values = ArrayDeque<TimedDouble>(128)
    private var sum = 0.0

    fun add(timestampMs: Long, value: Double) {
        values.addLast(TimedDouble(timestampMs, value))
        sum += value
        trim(timestampMs)
    }

    fun current(timestampMs: Long): Double {
        trim(timestampMs)
        return sum
    }

    private fun trim(nowMs: Long) {
        while (values.isNotEmpty() && values.first().timestampMs < nowMs - windowMs) {
            val v = values.removeFirst()
            sum -= v.value
        }
    }

    private data class TimedDouble(val timestampMs: Long, val value: Double)
}

internal class RollingTradeWindow(private val windowMs: Long) {
    private val trades = ArrayDeque<TradeSample>(256)
    private var totalQty = 0.0
    private var buyQty = 0.0
    private var sellQty = 0.0

    fun add(timestampMs: Long, qty: Double, isBuyerMaker: Boolean) {
        trades.addLast(TradeSample(timestampMs, qty, isBuyerMaker))
        totalQty += qty
        if (isBuyerMaker) {
            sellQty += qty
        } else {
            buyQty += qty
        }
        trim(timestampMs)
    }

    fun snapshot(timestampMs: Long): TradeWindowSnapshot {
        trim(timestampMs)
        return TradeWindowSnapshot(
            count = trades.size,
            volume = totalQty,
            buyVolume = buyQty,
            sellVolume = sellQty
        )
    }

    private fun trim(nowMs: Long) {
        while (trades.isNotEmpty() && trades.first().timestampMs < nowMs - windowMs) {
            val t = trades.removeFirst()
            totalQty -= t.quantity
            if (t.isBuyerMaker) {
                sellQty -= t.quantity
            } else {
                buyQty -= t.quantity
            }
        }
    }

    private data class TradeSample(val timestampMs: Long, val quantity: Double, val isBuyerMaker: Boolean)
}

internal data class TradeWindowSnapshot(
    val count: Int,
    val volume: Double,
    val buyVolume: Double,
    val sellVolume: Double
)

internal class RollingVolatility(windowMs: List<Long>) {
    private val windows = windowMs.distinct().sorted().map { VolWindow(it) }
    private var lastPrice: Double? = null

    fun addPrice(timestampMs: Long, price: Double) {
        val prev = lastPrice
        lastPrice = price
        if (prev == null || prev <= 0.0 || price <= 0.0) return

        val ret = ln(price / prev)
        windows.forEach { it.add(timestampMs, ret) }
    }

    fun sigma(windowMs: Long, timestampMs: Long): Double? {
        return windows.firstOrNull { it.windowMs == windowMs }?.sigma(timestampMs)
    }

    private class VolWindow(val windowMs: Long) {
        private val returns = ArrayDeque<ReturnSample>(256)
        private var sumSquares = 0.0

        fun add(timestampMs: Long, ret: Double) {
            returns.addLast(ReturnSample(timestampMs, ret))
            sumSquares += ret * ret
            trim(timestampMs)
        }

        fun sigma(timestampMs: Long): Double? {
            trim(timestampMs)
            val n = returns.size
            if (n < 2) return null
            return sqrt(sumSquares / n)
        }

        private fun trim(nowMs: Long) {
            while (returns.isNotEmpty() && returns.first().timestampMs < nowMs - windowMs) {
                val r = returns.removeFirst()
                sumSquares -= r.value * r.value
            }
        }

        private data class ReturnSample(val timestampMs: Long, val value: Double)
    }
}
