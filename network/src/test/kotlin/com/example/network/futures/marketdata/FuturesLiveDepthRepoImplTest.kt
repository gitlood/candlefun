package com.example.network.futures.marketdata

import com.example.network.dto.WsDepthUpdateData
import com.example.network.dto.WsEnvelopeDto
import com.example.network.futures.interfaces.FuturesWebSocketService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

class FuturesLiveDepthRepoImplTest {

    @Test
    fun `streamDepthUpdates filters by stream and type`() = runBlocking {
        val service = object : FuturesWebSocketService {
            override fun connect(streams: List<String>): Flow<WsEnvelopeDto> = flow {
                emit(createEnvelope("btcusdt@depth@100ms", 100))
            }

            override fun connectUserStream(listenKey: String) = flow<String> { }
        }
        val repo = FuturesLiveDepthRepoImpl(service)

        val results = repo.streamDepthUpdates(listOf("BTCUSDT"), speed = 100.milliseconds).toList()

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
