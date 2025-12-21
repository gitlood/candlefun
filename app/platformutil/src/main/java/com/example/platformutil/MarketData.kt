package com.example.platformutil

data class CandleJob(
    val symbol: String,
    val interval: String,
    val dbPath: String
)

val DEFAULT_SYMBOLS = listOf("ETHUSDT", "BTCUSDT")
val DEFAULT_INTERVALS = listOf("1m", "5m", "15m")

fun candleDbPath(symbol: String, interval: String): String {
    val safeSymbol = symbol.uppercase()
    val safeInterval = interval.lowercase()
    return "binance_${safeSymbol}_${safeInterval}.db"
}

fun resolveCandleDbPath(symbol: String, interval: String): String {
    val preferred = candleDbPath(symbol, interval)
    val legacy = "binance.db"
    return when {
        java.io.File(preferred).exists() -> preferred
        symbol.uppercase() == BINANCE_SYMBOL && interval.lowercase() == "5m" && java.io.File(legacy).exists() -> legacy
        else -> preferred
    }
}

fun orderBookDbPath(symbol: String): String {
    val safeSymbol = symbol.uppercase()
    return "orderbook_${safeSymbol}.db"
}

fun intervalToMillis(interval: String): Long {
    return when (interval.lowercase()) {
        "1m" -> 60_000L
        "3m" -> 3 * 60_000L
        "5m" -> 5 * 60_000L
        "15m" -> 15 * 60_000L
        "30m" -> 30 * 60_000L
        "1h" -> 60 * 60_000L
        "2h" -> 2 * 60 * 60_000L
        "4h" -> 4 * 60 * 60_000L
        "1d" -> 24 * 60 * 60_000L
        else -> error("Unsupported interval: $interval")
    }
}

fun intervalMillisToBinance(intervalMillis: Long): String {
    return when (intervalMillis) {
        60_000L -> "1m"
        3 * 60_000L -> "3m"
        5 * 60_000L -> "5m"
        15 * 60_000L -> "15m"
        30 * 60_000L -> "30m"
        60 * 60_000L -> "1h"
        2 * 60 * 60_000L -> "2h"
        4 * 60 * 60_000L -> "4h"
        24 * 60 * 60_000L -> "1d"
        else -> error("Unsupported intervalMillis: $intervalMillis")
    }
}
