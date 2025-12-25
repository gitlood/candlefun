package com.example.network.interfaces

import com.example.network.dto.WsDepthUpdateData
import kotlinx.coroutines.flow.Flow

interface LiveDepthRepo {
    fun streamDepthUpdates(symbols: List<String>, speedMs: Int = 100): Flow<WsDepthUpdateData>
}
