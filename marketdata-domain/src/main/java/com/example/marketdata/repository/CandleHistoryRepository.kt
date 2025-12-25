package com.example.marketdata.repository

import com.example.platform.model.CandleHistoryItem

interface CandleHistoryRepository {
    /**
     * Return candles for `symbol` from `fromOpenTimeInclusive` (ms) to "now".
     */
    suspend fun getCandlesFrom(
        symbol: String,
        fromOpenTimeInclusive: Long
    ): List<CandleHistoryItem>
}
