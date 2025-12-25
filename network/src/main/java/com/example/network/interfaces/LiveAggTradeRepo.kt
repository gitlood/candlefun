package com.example.network.interfaces

import com.example.network.dto.WsAggTradeData
import kotlinx.coroutines.flow.Flow

interface LiveAggTradeRepo {
    fun streamAggTrades(symbols: List<String>): Flow<WsAggTradeData>
}
