package com.example.survivor

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

class SurvivorCsvReplayer(
    private val file: File,
    private val speedup: Double = 1.0
) {
    fun stream(): Flow<SurvivorSnapshot> = flow {
        if (!file.exists()) return@flow
        val lines = file.readLines()
        if (lines.size <= 1) return@flow

        var prevTime: Long? = null
        for (i in 1 until lines.size) {
            val line = lines[i]
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

            val currentTime = snapshot.timestampMs
            val prev = prevTime
            if (prev != null) {
                val delta = currentTime - prev
                if (delta > 0) delay((delta / speedup).toLong())
            }
            prevTime = currentTime
            emit(snapshot)
        }
    }
}
