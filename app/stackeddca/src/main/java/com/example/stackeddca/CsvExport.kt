package com.example.stackeddca

import java.io.File
import java.time.Instant
import java.util.Locale

enum class EventType {
    ORDER,
    CANCEL,
    FILL,
    EXIT
}

data class BacktestEvent(
    val timestamp: Long,
    val symbol: String,
    val eventType: EventType,
    val layer: Int,
    val price: Double,
    val qty: Double,
    val notional: Double,
    val fee: Double,
    val slippagePct: Double,
    val avgEntry: Double,
    val volPctRef: Double,
    val cash: Double,
    val equity: Double,
    val pnl: Double,
    val reason: String
)

class CsvExporter(private val file: File) {
    fun write(events: List<BacktestEvent>) {
        file.parentFile?.mkdirs()
        file.printWriter().use { out ->
            out.println("timestamp,iso_time,symbol,event_type,layer,price,qty,notional,fee,slippage_pct,avg_entry,vol_pct_ref,cash,equity,pnl,reason")
            events.forEach { out.println(formatEvent(it)) }
        }
    }

    private fun formatEvent(event: BacktestEvent): String {
        val isoTime = Instant.ofEpochMilli(event.timestamp).toString()
        val reason = event.reason.replace(",", ";").replace("\n", " ").replace("\r", " ")
        return listOf(
            event.timestamp.toString(),
            isoTime,
            event.symbol,
            event.eventType.name,
            event.layer.toString(),
            formatDouble(event.price),
            formatDouble(event.qty),
            formatDouble(event.notional),
            formatDouble(event.fee),
            formatDouble(event.slippagePct),
            formatDouble(event.avgEntry),
            formatDouble(event.volPctRef),
            formatDouble(event.cash),
            formatDouble(event.equity),
            formatDouble(event.pnl),
            reason
        ).joinToString(",")
    }

    private fun formatDouble(value: Double): String {
        return String.format(Locale.US, "%.8f", value)
    }
}
