package com.example.network.interfaces

import com.example.network.dto.WsDepthUpdateData
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

interface LiveDepthRepo {
    fun streamDepthUpdates(symbols: List<String>, speed: Duration = 100.milliseconds): Flow<WsDepthUpdateData>
}
