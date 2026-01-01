package com.example.network

import com.example.network.dto.WsEnvelopeDto
import com.example.network.dto.WsKlineData
import com.example.network.dto.WsKlineDto
import com.example.network.interfaces.BinanceWebSocketService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveKlineRepoImplTest {

    @Test
    fun `streamClosed1mCandles emits only closed candles`() = runBlocking {
        // Arrange
        val service = object : BinanceWebSocketService {
            override fun connect(streams: List<String>): Flow<WsEnvelopeDto> = flow {
                // 1. Open candle (should be ignored)
                emit(createEnvelope(1000, false))
                // 2. Closed candle (should be emitted)
                emit(createEnvelope(2000, true))
                // 3. Open candle (ignored)
                emit(createEnvelope(3000, false))
            }
        }
        val repo = LiveKlineRepoImpl(service)

        // Act
        val results = repo.streamClosed1mCandles(listOf("BTCUSDT")).toList()

        // Assert
        assertEquals(1, results.size)
        val (symbol, candle) = results[0]
        assertEquals("BTCUSDT", symbol)
        assertEquals(2000L, candle.openTime)
        assertEquals(101.0, candle.close, 0.0001)
    }

    private fun createEnvelope(time: Long, closed: Boolean): WsEnvelopeDto {
        val kline = WsKlineDto(
            openTime = time,
            closeTime = time + 60000,
            symbol = "BTCUSDT",
            interval = "1m",
            firstTradeId = 0,
            lastTradeId = 0,
            open = "100.0",
            close = "101.0",
            high = "102.0",
            low = "99.0",
            volume = "1000.0",
            numberOfTrades = 50,
            isClosed = closed,
            quoteAssetVolume = "0",
            takerBuyBaseAssetVolume = "0",
            takerBuyQuoteAssetVolume = "0",
            ignore = "0"
        )
        val data = WsKlineData(symbol = "BTCUSDT", kline = kline)
        return WsEnvelopeDto(stream = "btcusdt@kline_1m", data = data)
    }
}
