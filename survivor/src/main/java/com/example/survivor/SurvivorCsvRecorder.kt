package com.example.survivor

import java.io.File

class SurvivorCsvRecorder(
    private val outputFile: File,
    private val recordEveryMs: Long
) {
    private var lastWriteMs: Long = 0L

    fun record(snapshot: SurvivorSnapshot) {
        if (recordEveryMs > 0L) {
            val now = snapshot.timestampMs
            if (lastWriteMs != 0L && now - lastWriteMs < recordEveryMs) return
            lastWriteMs = now
        }
        ensureHeader()
        outputFile.appendText(serialize(snapshot))
    }

    private fun ensureHeader() {
        if (outputFile.exists() && outputFile.length() > 0L) return
        outputFile.parentFile?.mkdirs()
        outputFile.writeText(
            "symbol,timestampMs,fundingRate,nextFundingTimeMs,markPrice,indexPrice,spreadPct,volatility,openInterest\n"
        )
    }

    private fun serialize(snapshot: SurvivorSnapshot): String {
        return buildString {
            append(snapshot.symbol).append(',')
            append(snapshot.timestampMs).append(',')
            append(snapshot.fundingRate).append(',')
            append(snapshot.nextFundingTimeMs).append(',')
            append(snapshot.markPrice).append(',')
            append(snapshot.indexPrice).append(',')
            append(snapshot.spreadPct).append(',')
            append(snapshot.volatility).append(',')
            append(snapshot.openInterest).append('\n')
        }
    }
}
