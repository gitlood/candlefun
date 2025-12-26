package com.example.network

import com.example.network.dto.WsDepthUpdateData
import com.example.network.interfaces.BinanceWebSocketService
import com.example.network.interfaces.LiveDepthRepo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlin.time.Duration

internal class LiveDepthRepoImpl(
    private val ws: BinanceWebSocketService
) : LiveDepthRepo {
    override fun streamDepthUpdates(symbols: List<String>, speed: Duration): Flow<WsDepthUpdateData> {
        val speedMs = speed.inWholeMilliseconds
        val streams = symbols.map { "${it.lowercase()}@depth@${speedMs}ms" }
        return ws.connect(streams)
            .mapNotNull { env ->
                val stream = env.stream ?: return@mapNotNull null
                if (!stream.contains("@depth")) return@mapNotNull null
                
                env.data as? WsDepthUpdateData
            }
    }
}
