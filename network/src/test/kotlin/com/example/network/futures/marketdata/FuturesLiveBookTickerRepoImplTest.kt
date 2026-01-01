package com.example.network.futures.marketdata

import com.example.network.dto.WsBookTickerData
import com.example.network.dto.WsEnvelopeDto
import com.example.network.dto.WsUnknownData
import com.example.network.futures.interfaces.FuturesWebSocketService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class FuturesLiveBookTickerRepoImplTest {

    @Test
    fun `streamBookTickers filters by stream and type`() = runBlocking {
        val service = object : FuturesWebSocketService {
            override fun connect(streams: List<String>): Flow<WsEnvelopeDto> = flow {
                emit(createEnvelope("btcusdt@bookTicker", "25000.0"))
                emit(WsEnvelopeDto(stream = "btcusdt@depth", data = WsUnknownData("BTCUSDT")))
            }

            override fun connectUserStream(listenKey: String) = flow<String> { }
        }
        val repo = FuturesLiveBookTickerRepoImpl(service)

        val results = repo.streamBookTickers(listOf("BTCUSDT")).toList()

        assertEquals(1, results.size)
        assertEquals("25000.0", results[0].bestBidPrice)
    }

    private fun createEnvelope(streamName: String, bid: String): WsEnvelopeDto {
        val data = WsBookTickerData(
            symbol = "BTCUSDT",
            bestBidPrice = bid,
            bestBidQty = "1.0",
            bestAskPrice = "25001.0",
            bestAskQty = "1.0"
        )
        return WsEnvelopeDto(stream = streamName, data = data)
    }
}
