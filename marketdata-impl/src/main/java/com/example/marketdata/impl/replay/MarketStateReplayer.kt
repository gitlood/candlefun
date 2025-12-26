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
            val parts = lines[i].split(',')
            if (parts.size < 26) continue

            val state = MarketState(
                symbol = parts[0],
                timestampMs = parts[1].toLong(),
                eventTimeMs = parts[2].toLong().takeIf { it > 0L },
                bestBidPrice = parts[3].toDoubleOrNull(),
                bestBidQty = parts[4].toDoubleOrNull(),
                bestAskPrice = parts[5].toDoubleOrNull(),
                bestAskQty = parts[6].toDoubleOrNull(),
                midPrice = parts[7].toDoubleOrNull(),
                spread = parts[8].toDoubleOrNull(),
                microPrice = parts[9].toDoubleOrNull(),
                depthImbalance = parts[10].toDoubleOrNull(),
                ofi1s = parts[11].toDoubleOrNull() ?: 0.0,
                tradeCount1s = parts[12].toIntOrNull() ?: 0,
                tradeVolume1s = parts[13].toDoubleOrNull() ?: 0.0,
                tradeImbalance1s = parts[14].toDoubleOrNull() ?: 0.0,
                lastTradePrice = parts[15].toDoubleOrNull(),
                lastTradeQty = parts[16].toDoubleOrNull(),
                lastTradeIsBuyerMaker = parts[17].toBooleanStrictOrNull(),
                vol1s = parts[18].toDoubleOrNull(),
                vol5s = parts[19].toDoubleOrNull(),
                vol10s = parts[20].toDoubleOrNull(),
                vol1m = parts[21].toDoubleOrNull(),
                vol5m = parts[22].toDoubleOrNull(),
                bookUpdateId = parts[23].toLongOrNull() ?: 0L,
                bidLevels = parseLevels(parts[24]),
                askLevels = parseLevels(parts[25])
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
