package com.example.network.futures.marketdata

import com.example.network.dto.WsAggTradeData
import com.example.network.futures.interfaces.FuturesLiveAggTradeRepo
import com.example.network.futures.interfaces.FuturesWebSocketService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

internal class FuturesLiveAggTradeRepoImpl(
    private val ws: FuturesWebSocketService
) : FuturesLiveAggTradeRepo {
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
