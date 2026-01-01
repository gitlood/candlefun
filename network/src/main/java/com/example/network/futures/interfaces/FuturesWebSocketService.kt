package com.example.network.futures.interfaces

import com.example.network.dto.WsEnvelopeDto
import kotlinx.coroutines.flow.Flow

interface FuturesWebSocketService {
    fun connect(streams: List<String>): Flow<WsEnvelopeDto>
    fun connectUserStream(listenKey: String): Flow<String>
}
