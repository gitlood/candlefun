package com.example.network

import com.example.network.dto.WsAggTradeData
import com.example.network.interfaces.BinanceWebSocketService
import com.example.network.interfaces.LiveAggTradeRepo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

internal class LiveAggTradeRepoImpl(
    private val ws: BinanceWebSocketService
) : LiveAggTradeRepo {
    override fun streamAggTrades(symbols: List<String>): Flow<WsAggTradeData> {
        val streams = symbols.map { "${it.lowercase()}@aggTrade" }
        return ws.connect(streams)
            .mapNotNull { env ->
                val stream = env.stream ?: return@mapNotNull null
                if (!stream.endsWith("@aggTrade")) return@mapNotNull null

                env.data as? WsAggTradeData
            }
    }
}
