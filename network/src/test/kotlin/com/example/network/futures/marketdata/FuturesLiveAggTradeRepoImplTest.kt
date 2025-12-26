package com.example.network.futures.marketdata

import com.example.network.dto.WsAggTradeData
import com.example.network.dto.WsEnvelopeDto
import com.example.network.dto.WsUnknownData
import com.example.network.futures.interfaces.FuturesWebSocketService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class FuturesLiveAggTradeRepoImplTest {

    @Test
    fun `streamAggTrades filters by stream and type`() = runBlocking {
        val service = object : FuturesWebSocketService {
            override fun connect(streams: List<String>): Flow<WsEnvelopeDto> = flow {
                emit(createEnvelope("btcusdt@aggTrade", 1))
                emit(WsEnvelopeDto(stream = "btcusdt@bookTicker", data = WsUnknownData("BTCUSDT")))
            }

            override fun connectUserStream(listenKey: String) = flow<String> { }
        }
        val repo = FuturesLiveAggTradeRepoImpl(service)

        val results = repo.streamAggTrades(listOf("BTCUSDT")).toList()

        assertEquals(1, results.size)
        assertEquals(1L, results[0].aggTradeId)
    }

    private fun createEnvelope(streamName: String, aggTradeId: Long): WsEnvelopeDto {
        val data = WsAggTradeData(
            symbol = "BTCUSDT",
            aggTradeId = aggTradeId,
            price = "25000.0",
            quantity = "0.5",
            firstTradeId = 10,
            lastTradeId = 11,
            tradeTime = 123,
            isBuyerMaker = false
        )
        return WsEnvelopeDto(stream = streamName, data = data)
    }
}
