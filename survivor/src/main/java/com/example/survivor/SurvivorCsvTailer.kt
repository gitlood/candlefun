package com.example.survivor

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

class SurvivorCsvTailer(
    private val file: File,
    private val pollMs: Long = 1_000L
) {
    fun stream(): Flow<SurvivorSnapshot> = flow {
        if (!file.exists()) return@flow
        var offset = 0L
        while (true) {
            if (!file.exists()) {
                delay(pollMs)
                continue
            }
            val length = file.length()
            if (length < offset) {
                offset = 0L
            }
            if (length == offset) {
                delay(pollMs)
                continue
            }
            val lines = file.inputStream().buffered().use { input ->
                input.skip(offset)
                input.reader().readLines()
            }
            offset = length
            for (line in lines) {
                if (line.startsWith("symbol,")) continue
                val parts = line.split(',')
                if (parts.size < 9) continue
                val snapshot = SurvivorSnapshot(
                    symbol = parts[0],
                    timestampMs = parts[1].toLongOrNull() ?: continue,
                    fundingRate = parts[2].toDoubleOrNull() ?: continue,
                    nextFundingTimeMs = parts[3].toLongOrNull() ?: continue,
                    markPrice = parts[4].toDoubleOrNull() ?: continue,
                    indexPrice = parts[5].toDoubleOrNull() ?: continue,
                    spreadPct = parts[6].toDoubleOrNull() ?: 0.0,
                    volatility = parts[7].toDoubleOrNull() ?: 0.0,
                    openInterest = parts[8].toDoubleOrNull() ?: 0.0
                )
                emit(snapshot)
            }
            delay(pollMs)
        }
    }
}
