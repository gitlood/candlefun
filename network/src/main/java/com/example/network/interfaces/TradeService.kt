package com.example.network.interfaces

import com.example.platform.model.Trade

interface TradeService {
    suspend fun getAggTrades(symbol: String, fromId: Long?, limit: Int): List<Trade>
}
