package com.example.marketdata.impl.replay

import com.example.platform.model.BookLevel
import com.example.platform.model.MarketState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

class MarketStateReplayer(
    private val file: File,
    private val speedup: Double = 1.0
) {
    fun stream(): Flow<MarketState> = flow {
        if (!file.exists()) return@flow
        val lines = file.readLines()
        if (lines.size <= 1) return@flow

        var prevTime: Long? = null
        for (i in 1 until lines.size) {
            val line = lines[i]
            val parts = line.split(',')
            val normalized = if (line.endsWith(",")) parts + "" else parts
            if (normalized.size < 26) continue

            val state = MarketState(
                symbol = normalized[0],
                timestampMs = normalized[1].toLong(),
                eventTimeMs = normalized[2].toLong().takeIf { it > 0L },
                bestBidPrice = normalized[3].toDoubleOrNull(),
                bestBidQty = normalized[4].toDoubleOrNull(),
                bestAskPrice = normalized[5].toDoubleOrNull(),
                bestAskQty = normalized[6].toDoubleOrNull(),
                midPrice = normalized[7].toDoubleOrNull(),
                spread = normalized[8].toDoubleOrNull(),
                microPrice = normalized[9].toDoubleOrNull(),
                depthImbalance = normalized[10].toDoubleOrNull(),
                ofi1s = normalized[11].toDoubleOrNull() ?: 0.0,
                tradeCount1s = normalized[12].toIntOrNull() ?: 0,
                tradeVolume1s = normalized[13].toDoubleOrNull() ?: 0.0,
                tradeImbalance1s = normalized[14].toDoubleOrNull() ?: 0.0,
                lastTradePrice = normalized[15].toDoubleOrNull(),
                lastTradeQty = normalized[16].toDoubleOrNull(),
                lastTradeIsBuyerMaker = normalized[17].toBooleanStrictOrNull(),
                vol1s = normalized[18].toDoubleOrNull(),
                vol5s = normalized[19].toDoubleOrNull(),
                vol10s = normalized[20].toDoubleOrNull(),
                vol1m = normalized[21].toDoubleOrNull(),
                vol5m = normalized[22].toDoubleOrNull(),
                bookUpdateId = normalized[23].toLongOrNull() ?: 0L,
                bidLevels = parseLevels(normalized[24]),
                askLevels = parseLevels(normalized[25])
            )

            val currentTime = state.eventTimeMs ?: state.timestampMs
            val prev = prevTime
            if (prev != null) {
                val delta = currentTime - prev
                if (delta > 0) delay((delta / speedup).toLong())
            }
            prevTime = currentTime
            emit(state)
        }
    }

    private fun parseLevels(raw: String): List<BookLevel> {
        if (raw.isBlank()) return emptyList()
        return raw.split(';').mapNotNull { level ->
            val pair = level.split(':')
            if (pair.size != 2) return@mapNotNull null
            val price = pair[0].toDoubleOrNull() ?: return@mapNotNull null
            val qty = pair[1].toDoubleOrNull() ?: return@mapNotNull null
            BookLevel(price = price, quantity = qty)
        }
    }
}
