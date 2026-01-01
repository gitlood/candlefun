package com.example.network.dto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WsDataSerializerTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `deserialize kline envelope`() {
        // e.g. "e": "kline" is not strictly required by your serializer logic, it checks for "k"
        val payload = """
            {
              "stream": "btcusdt@kline_1m",
              "data": {
                "e": "kline",
                "E": 123456789,
                "s": "BTCUSDT",
                "k": {
                  "t": 123400000,
                  "T": 123460000,
                  "s": "BTCUSDT",
                  "i": "1m",
                  "f": 100,
                  "L": 200,
                  "o": "100.0",
                  "c": "101.0",
                  "h": "102.0",
                  "l": "99.0",
                  "v": "1000",
                  "n": 10,
                  "x": true,
                  "q": "100000",
                  "V": "500",
                  "Q": "50000",
                  "B": "0"
                }
              }
            }
        """.trimIndent()

        val envelope = json.decodeFromString(WsEnvelopeDto.serializer(), payload)
        assertTrue(envelope.data is WsKlineData)
        val data = envelope.data as WsKlineData
        assertEquals("BTCUSDT", data.symbol)
        assertEquals(123400000L, data.kline.openTime)
        assertEquals("101.0", data.kline.close)
    }

    @Test
    fun `deserialize bookTicker envelope`() {
        val payload = """
            {
                "stream": "btcusdt@bookTicker",
              "data": {
                "E": 123456789,
                "u": 400900217,
                "s": "BTCUSDT",
                "b": "25.35190000",
                "B": "31.21000000",
                "a": "25.36520000",
                "A": "40.66000000"
              }
            }
        """.trimIndent()

        val envelope = json.decodeFromString(WsEnvelopeDto.serializer(), payload)
        assertTrue(envelope.data is WsBookTickerData)
        val data = envelope.data as WsBookTickerData
        assertEquals("BTCUSDT", data.symbol)
        assertEquals("25.35190000", data.bestBidPrice)
        assertEquals(123456789L, data.eventTime)
    }

    @Test
    fun `deserialize depthUpdate envelope`() {
        val payload = """
            {
              "stream": "btcusdt@depth",
              "data": {
                "e": "depthUpdate",
                "E": 123456789,
                "s": "BTCUSDT",
                "U": 157,
                "u": 160,
                "b": [["0.0024", "10"]],
                "a": [["0.0026", "100"]]
              }
            }
        """.trimIndent()

        val envelope = json.decodeFromString(WsEnvelopeDto.serializer(), payload)
        assertTrue(envelope.data is WsDepthUpdateData)
        val data = envelope.data as WsDepthUpdateData
        assertEquals("BTCUSDT", data.symbol)
        assertEquals(123456789L, data.eventTime)
        assertEquals(157L, data.firstUpdateId)
        assertEquals(1, data.bids.size)
        assertEquals("0.0024", data.bids[0][0])
    }

    @Test
    fun `deserialize aggTrade envelope`() {
        val payload = """
            {
              "stream": "btcusdt@aggTrade",
              "data": {
                "e": "aggTrade",
                "E": 123456789,
                "s": "BTCUSDT",
                "a": 1001,
                "p": "25.35190000",
                "q": "0.123",
                "f": 100,
                "l": 105,
                "T": 123456790,
                "m": true,
                "M": true
              }
            }
        """.trimIndent()

        val envelope = json.decodeFromString(WsEnvelopeDto.serializer(), payload)
        assertTrue(envelope.data is WsAggTradeData)
        val data = envelope.data as WsAggTradeData
        assertEquals("BTCUSDT", data.symbol)
        assertEquals(123456789L, data.eventTime)
        assertEquals(1001L, data.aggTradeId)
        assertEquals("25.35190000", data.price)
    }

    @Test
    fun `deserialize unknown envelope`() {
        val payload = """
            {
              "stream": "unknown",
              "data": {
                "foo": "bar"
              }
            }
        """.trimIndent()

        val envelope = json.decodeFromString(WsEnvelopeDto.serializer(), payload)
        assertTrue(envelope.data is WsUnknownData)
    }
}
