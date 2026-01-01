package com.example.network

import com.example.network.dto.WsBookTickerData
import com.example.network.dto.WsData
import com.example.network.dto.WsEnvelopeDto
import com.example.network.interfaces.BinanceWebSocketService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveBookTickerRepoImplTest {

    @Test
    fun `streamBookTickers filters by stream and type`() = runBlocking {
        val service = object : BinanceWebSocketService {
            override fun connect(streams: List<String>): Flow<WsEnvelopeDto> = flow {
                // 1. Correct stream, correct data
                emit(createEnvelope("btcusdt@bookTicker", "25000.0"))
                // 2. Wrong stream
                emit(createEnvelope("btcusdt@kline_1m", "ignored"))
            }
        }
        val repo = LiveBookTickerRepoImpl(service)

        val results = repo.streamBookTickers(listOf("BTCUSDT")).toList()

        assertEquals(1, results.size)
        assertEquals("25000.0", results[0].bestBidPrice)
    }

    private fun createEnvelope(streamName: String, bid: String): WsEnvelopeDto {
        // If it's the bookTicker stream, use WsBookTickerData, else some dummy or null
        val data: WsData = if (streamName.endsWith("bookTicker")) {
             WsBookTickerData(
                symbol = "BTCUSDT",
                bestBidPrice = bid,
                bestBidQty = "1.0",
                bestAskPrice = "25001.0",
                bestAskQty = "1.0"
            )
        } else {
            // Just use a dummy type or rely on the Repo filtering by stream string first
             com.example.network.dto.WsUnknownData(symbol = "BTCUSDT")
        }
        
        return WsEnvelopeDto(stream = streamName, data = data)
    }
}
