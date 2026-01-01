package com.example.network.candlecollector

class SymbolLogger(
    symbol: String,
    interval: String,
    private val statusEveryMs: Long,
    private val errorEveryMs: Long
) {
    private var lastStatus = 0L
    private var lastError = 0L

    // Fixed width so columns line up without leading zeros.
    // Example: BTCUSDT  1m  |
    private val prefix = "%-8s %-3s |".format(symbol, interval)

    fun info(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastStatus > statusEveryMs) {
            println("$prefix $msg")
            lastStatus = now
        }
    }

    fun error(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastError > errorEveryMs) {
            System.err.println("$prefix ERROR: $msg")
            lastError = now
        }
    }
}
