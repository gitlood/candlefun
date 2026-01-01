package com.example.network.interfaces

import com.example.network.dto.WsBookTickerData
import kotlinx.coroutines.flow.Flow

interface LiveBookTickerRepo {
    fun streamBookTickers(symbols: List<String>): Flow<WsBookTickerData>
}
