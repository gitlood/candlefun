package com.example.network.futures.marketdata

import com.example.network.dto.WsBookTickerData
import com.example.network.futures.interfaces.FuturesLiveBookTickerRepo
import com.example.network.futures.interfaces.FuturesWebSocketService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

internal class FuturesLiveBookTickerRepoImpl(
    private val ws: FuturesWebSocketService
) : FuturesLiveBookTickerRepo {
    override fun streamBookTickers(symbols: List<String>): Flow<WsBookTickerData> {
        val streams = symbols.map { "${it.lowercase()}@bookTicker" }
        return ws.connect(streams)
            .mapNotNull { env ->
                val stream = env.stream ?: return@mapNotNull null
                if (!stream.endsWith("@bookTicker")) return@mapNotNull null
                env.data as? WsBookTickerData
            }
    }
}
