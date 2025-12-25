package com.example.marketdata.repository

import com.example.platform.model.Trade

interface TradeHistoryRepository {
    suspend fun getTradesFrom(symbol: String, fromTimeInclusive: Long): List<Trade>
}
