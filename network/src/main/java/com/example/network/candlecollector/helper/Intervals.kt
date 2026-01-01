package com.example.network.candlecollector.helper

object Intervals {
    /**
     * Converts a Binance interval string (e.g., "1m", "1h") to milliseconds.
     * @throws IllegalArgumentException if the format is invalid or unknown.
     */
    fun toMs(interval: String): Long {
        if (interval.isEmpty()) throw IllegalArgumentException("Interval cannot be empty")
        
        val unit = interval.last()
        val numStr = interval.dropLast(1)
        val num = numStr.toLongOrNull() 
            ?: throw IllegalArgumentException("Invalid interval number: $numStr in '$interval'")

        return when (unit) {
            'm' -> num * 60 * 1000L
            'h' -> num * 60 * 60 * 1000L
            'd' -> num * 24 * 60 * 60 * 1000L
            'w' -> num * 7 * 24 * 60 * 60 * 1000L
            'M' -> throw IllegalArgumentException("Monthly intervals ('M') not supported for ms stepping due to variable length")
            else -> throw IllegalArgumentException("Unknown interval unit: '$unit' in '$interval'")
        }
    }
}
