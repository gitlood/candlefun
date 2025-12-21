package com.example.tradebot

import com.example.platformutil.model.Candle

object TradeBotMath {
    fun barsFromMinutes(minutes: Int, intervalMillis: Long): Int {
        val m = minutes.coerceAtLeast(1)
        val ms = intervalMillis.coerceAtLeast(1L)
        return ((m * 60_000L) / ms).toInt().coerceAtLeast(1)
    }

    fun inferIntervalMillis(sortedCandles: List<Candle>): Long {
        if (sortedCandles.size < 2) return 300_000L // 5m default
        val diffs = sortedCandles.zipWithNext { a, b -> b.openTime - a.openTime }.filter { it > 0 }
        if (diffs.isEmpty()) return 300_000L
        return diffs.sorted()[diffs.size / 2] // median
    }
}
