package com.example.network.futures.marketdata

import com.example.network.dto.WsDepthUpdateData
import com.example.network.futures.interfaces.FuturesLiveDepthRepo
import com.example.network.futures.interfaces.FuturesWebSocketService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

internal class FuturesLiveDepthRepoImpl(
    private val ws: FuturesWebSocketService
) : FuturesLiveDepthRepo {
    override fun streamDepthUpdates(symbols: List<String>, speedMs: Int): Flow<WsDepthUpdateData> {
        val streams = symbols.map { "${it.lowercase()}@depth@${speedMs}ms" }
        return ws.connect(streams)
            .mapNotNull { env ->
                val stream = env.stream ?: return@mapNotNull null
                if (!stream.contains("@depth")) return@mapNotNull null
                env.data as? WsDepthUpdateData
            }
    }
}
