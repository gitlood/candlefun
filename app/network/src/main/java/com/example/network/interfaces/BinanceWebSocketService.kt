package com.example.network.interfaces

import com.example.network.dto.WsEnvelopeDto
import kotlinx.coroutines.flow.Flow

interface BinanceWebSocketService {
    suspend fun connect(streams: List<String>): Flow<WsEnvelopeDto>
}
