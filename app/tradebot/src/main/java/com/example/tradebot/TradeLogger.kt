package com.example.tradebot

import com.example.platformutil.model.ExecutionMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

interface TradeLogger {
    fun logEntry(event: TradeEntry)
    fun logExit(event: TradeExit)
}

data class TradeEntry(
    val botName: String,
    val symbol: String,
    val mode: ExecutionMode,
    val pattern: String,
    val entryTime: Long,
    val entryPrice: Double,
    val quantity: String,
    val spreadBps: Double?,
    val imbalance10: Double?,
    val candleOpenTime: Long,
    val timestamp: Long = System.currentTimeMillis()
)

data class TradeExit(
    val botName: String,
    val symbol: String,
    val mode: ExecutionMode,
    val pattern: String,
    val reason: String,
    val entryTime: Long,
    val exitTime: Long,
    val entryPrice: Double,
    val exitPrice: Double,
    val netPct: Double,
    val durationMs: Long,
    val quantity: String,
    val timestamp: Long = System.currentTimeMillis()
)

object NoopTradeLogger : TradeLogger {
    override fun logEntry(event: TradeEntry) {}
    override fun logExit(event: TradeExit) {}
}

class CsvTradeLogger(
    path: String = "trade-events.csv",
    private val dateFormat: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
) : TradeLogger {

    private val file = File(path)
    private val initialized = AtomicBoolean(false)

    init {
        file.parentFile?.takeIf { !it.exists() }?.mkdirs()
    }

    override fun logEntry(event: TradeEntry) {
        log(
            event.timestamp,
            event.botName,
            event.symbol,
            event.mode,
            "ENTRY",
            event.pattern,
            event.entryPrice,
            event.quantity,
            reason = "pattern",
            entryTime = event.entryTime,
            exitTime = null,
            entryPrice = event.entryPrice,
            exitPrice = null,
            netPct = null,
            durationMs = null,
            spreadBps = event.spreadBps,
            imbalance10 = event.imbalance10,
            candleOpen = event.candleOpenTime
        )
    }

    override fun logExit(event: TradeExit) {
        log(
            event.timestamp,
            event.botName,
            event.symbol,
            event.mode,
            "EXIT",
            event.pattern,
            event.exitPrice,
            event.quantity,
            reason = event.reason,
            entryTime = event.entryTime,
            exitTime = event.exitTime,
            entryPrice = event.entryPrice,
            exitPrice = event.exitPrice,
            netPct = event.netPct,
            durationMs = event.durationMs,
            spreadBps = null,
            imbalance10 = null,
            candleOpen = event.exitTime
        )
    }

    private fun log(
        timestamp: Long,
        botName: String,
        symbol: String,
        mode: ExecutionMode,
        eventType: String,
        pattern: String,
        price: Double,
        quantity: String,
        reason: String,
        entryTime: Long?,
        exitTime: Long?,
        entryPrice: Double?,
        exitPrice: Double?,
        netPct: Double?,
        durationMs: Long?,
        spreadBps: Double?,
        imbalance10: Double?,
        candleOpen: Long
    ) {
        if (initialized.compareAndSet(false, true)) {
            file.appendText(
                "timestamp,botName,symbol,mode,event,pattern,price,quantity,reason,entryTime,exitTime,entryPrice,exitPrice,netPct,durationMs,spreadBps,imbalance10,candleOpen\n"
            )
        }
        val row = buildString {
            append(escape(dateFormat.format(timestamp)))
            append(',')
            append(escape(botName))
            append(',')
            append(escape(symbol))
            append(',')
            append(escape(mode.name))
            append(',')
            append(escape(eventType))
            append(',')
            append(escape(pattern))
            append(',')
            append(escape(price.toString()))
            append(',')
            append(escape(quantity))
            append(',')
            append(escape(reason))
            append(',')
            append(entryTime?.toString() ?: "")
            append(',')
            append(exitTime?.toString() ?: "")
            append(',')
            append(entryPrice?.toString() ?: "")
            append(',')
            append(exitPrice?.toString() ?: "")
            append(',')
            append(netPct?.toString() ?: "")
            append(',')
            append(durationMs?.toString() ?: "")
            append(',')
            append(spreadBps?.toString() ?: "")
            append(',')
            append(imbalance10?.toString() ?: "")
            append(',')
            append(candleOpen.toString())
        }
        synchronized(this) {
            file.appendText(row)
            file.appendText("\n")
        }
    }

    private fun escape(value: String?): String {
        if (value == null) return ""
        val escaped = value.replace("\"", "\"\"")
        return "\"$escaped\""
    }
}
