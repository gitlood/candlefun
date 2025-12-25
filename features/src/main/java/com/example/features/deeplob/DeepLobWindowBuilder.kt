package com.example.features.deeplob

import com.example.platform.model.MarketState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class DeepLobWindowBuilder(
    private val config: DeepLobConfig = DeepLobConfig()
) {
    fun streamSamples(states: Flow<MarketState>): Flow<DeepLobSample> = flow {
        val buffer = ArrayDeque<MarketState>(config.windowSize)
        states.collect { state ->
            buffer.addLast(state)
            if (buffer.size > config.windowSize) buffer.removeFirst()

            if (buffer.size >= config.minEvents) {
                emit(
                    DeepLobSample(
                        symbol = state.symbol,
                        endTimeMs = state.eventTimeMs ?: state.timestampMs,
                        window = buffer.toList()
                    )
                )
            }
        }
    }
}
