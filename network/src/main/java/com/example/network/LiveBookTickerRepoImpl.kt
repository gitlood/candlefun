package com.example.network

import com.example.network.dto.WsBookTickerData
import com.example.network.interfaces.BinanceWebSocketService
import com.example.network.interfaces.LiveBookTickerRepo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

internal class LiveBookTickerRepoImpl(
    private val ws: BinanceWebSocketService
) : LiveBookTickerRepo {
    override fun streamBookTickers(symbols: List<String>): Flow<WsBookTickerData> {
        val streams = symbols.map { "${it.lowercase()}@bookTicker" }
        return ws.connect(streams)
            .mapNotNull { env ->
                // Check stream name if necessary, but type check is robust enough here
                val stream = env.stream ?: return@mapNotNull null
                if (!stream.endsWith("@bookTicker")) return@mapNotNull null
                
                env.data as? WsBookTickerData
            }
    }
}
