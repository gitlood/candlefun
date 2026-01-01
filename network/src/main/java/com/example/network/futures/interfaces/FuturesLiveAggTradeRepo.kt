package com.example.network.futures.interfaces

import com.example.network.dto.WsAggTradeData
import kotlinx.coroutines.flow.Flow

interface FuturesLiveAggTradeRepo {
    fun streamAggTrades(symbols: List<String>): Flow<WsAggTradeData>
}
