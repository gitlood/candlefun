package com.example.marketdata.repository

import com.example.marketdata.model.Symbol
import com.example.platform.model.Trade

interface TradeHistoryRepository {
    suspend fun getTrades(
        symbol: Symbol,
        fromTimeInclusive: Long,
        toTimeExclusive: Long? = null,
        limit: Int? = null
    ): List<Trade>
}
