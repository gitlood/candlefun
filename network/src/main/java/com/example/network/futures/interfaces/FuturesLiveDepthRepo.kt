package com.example.network.futures.interfaces

import com.example.network.dto.WsDepthUpdateData
import kotlinx.coroutines.flow.Flow

interface FuturesLiveDepthRepo {
    fun streamDepthUpdates(symbols: List<String>, speedMs: Int = 100): Flow<WsDepthUpdateData>
}
