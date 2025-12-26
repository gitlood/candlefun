package com.example.network.futures.interfaces

import com.example.network.dto.WsBookTickerData
import kotlinx.coroutines.flow.Flow

interface FuturesLiveBookTickerRepo {
    fun streamBookTickers(symbols: List<String>): Flow<WsBookTickerData>
}
