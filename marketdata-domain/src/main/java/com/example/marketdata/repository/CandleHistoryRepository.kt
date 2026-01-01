package com.example.marketdata.repository

import com.example.marketdata.model.Symbol
import com.example.platform.model.CandleHistoryItem

interface CandleHistoryRepository {
    /**
     * Return candles for `symbol` between `fromOpenTimeInclusive` (ms) and `toOpenTimeExclusive` (ms).
     */
    suspend fun getCandles(
        symbol: Symbol,
        fromOpenTimeInclusive: Long,
        toOpenTimeExclusive: Long? = null,
        limit: Int? = null
    ): List<CandleHistoryItem>
}
