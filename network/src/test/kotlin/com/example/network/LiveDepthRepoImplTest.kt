package com.example.network

import com.example.network.dto.WsDepthUpdateData
import com.example.network.dto.WsEnvelopeDto
import com.example.network.interfaces.BinanceWebSocketService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveDepthRepoImplTest {

    @Test
    fun `streamDepthUpdates filters and maps correctly`() = runBlocking {
        val service = object : BinanceWebSocketService {
            override fun connect(streams: List<String>): Flow<WsEnvelopeDto> = flow {
                emit(createEnvelope("btcusdt@depth@100ms", 100))
            }
        }
        val repo = LiveDepthRepoImpl(service)

        val results = repo.streamDepthUpdates(listOf("BTCUSDT")).toList()

        assertEquals(1, results.size)
        assertEquals(100L, results[0].firstUpdateId)
    }

    private fun createEnvelope(streamName: String, uId: Long): WsEnvelopeDto {
        val data = WsDepthUpdateData(
            symbol = "BTCUSDT",
            firstUpdateId = uId,
            finalUpdateId = uId + 10,
            bids = emptyList(),
            asks = emptyList()
        )
        return WsEnvelopeDto(stream = streamName, data = data)
    }
}
