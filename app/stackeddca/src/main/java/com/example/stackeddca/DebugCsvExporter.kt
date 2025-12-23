package com.example.stackeddca

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class DailyDebugSummary(
    val symbol: String,
    val dayStart: Long,
    var volPct: Double = 0.0,
    var priceRef: Double = 0.0,
    var anchorPrice: Double = 0.0,
    var rawLimit: Double = 0.0,
    var limitPrice: Double = 0.0,
    var entryAttempted: Boolean = false,
    var entryFilled: Boolean = false,
    var fillPrice: Double = 0.0,
    var exitReason: String = "",
    var exitPrice: Double = 0.0,
    var dayPeakEquity: Double = 0.0,
    var dayLowEquity: Double = 0.0,
    var dayDrawdownPct: Double = 0.0,
    var maxLayers: Int = 0,
    var comment: String = "",
    var trendSma: Double = 0.0,
    var trendOk: Boolean = true,
    var trendDays: Int = 0
)

class DebugCsvExporter(private val file: File) {
    private val header =
        "symbol,dayStart,isoDate,volPct,priceRef,anchorPrice,rawLimit,limitPrice,entryAttempted,entryFilled,fillPrice,exitReason,exitPrice,dayPeakEquity,dayLowEquity,dayDrawdownPct,maxLayers,comment,trendSma,trendOk,trendDays"

    fun write(records: List<DailyDebugSummary>) {
        if (records.isEmpty()) return
        file.parentFile?.mkdirs()
        file.printWriter().use { out ->
            out.println(header)
            records.forEach { out.println(formatRecord(it)) }
        }
    }

    private fun formatRecord(record: DailyDebugSummary): String {
        val iso = Instant.ofEpochMilli(record.dayStart)
            .atZone(ZoneOffset.UTC)
            .toLocalDate()
            .format(DateTimeFormatter.ISO_DATE)
        return listOf(
            record.symbol,
            record.dayStart.toString(),
            iso,
            format(record.volPct),
            format(record.priceRef),
            format(record.anchorPrice),
            format(record.rawLimit),
            format(record.limitPrice),
            record.entryAttempted,
            record.entryFilled,
            format(record.fillPrice),
            record.exitReason,
            format(record.exitPrice),
            format(record.dayPeakEquity),
            format(record.dayLowEquity),
            format(record.dayDrawdownPct),
            record.maxLayers,
            record.comment,
            format(record.trendSma),
            record.trendOk,
            record.trendDays
        ).joinToString(",")
    }

    private fun format(value: Double): String = String.format("%f", value)
}
