package com.example.network.dto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class NewDtoSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `deserialize WsBookTickerDto`() {
        val payload = """
            {
              "s": "BTCUSDT",
              "E": 123,
              "u": 5,
              "b": "100.0",
              "B": "1.0",
              "a": "101.0",
              "A": "2.0"
            }
        """.trimIndent()

        val dto = json.decodeFromString(WsBookTickerDto.serializer(), payload)

        assertEquals("BTCUSDT", dto.symbol)
        assertEquals(123L, dto.eventTime)
        assertEquals("100.0", dto.bestBidPrice)
        assertEquals("101.0", dto.bestAskPrice)
    }

    @Test
    fun `deserialize WsDepthUpdateDto`() {
        val payload = """
            {
              "s": "BTCUSDT",
              "E": 321,
              "U": 10,
              "u": 11,
              "b": [["100.0", "1.0"]],
              "a": [["101.0", "2.0"]]
            }
        """.trimIndent()

        val dto = json.decodeFromString(WsDepthUpdateDto.serializer(), payload)

        assertEquals("BTCUSDT", dto.symbol)
        assertEquals(10L, dto.firstUpdateId)
        assertEquals("100.0", dto.bids[0][0])
    }

    @Test
    fun `deserialize OrderDto`() {
        val payload = """
            {
              "symbol": "BTCUSDT",
              "orderId": 1,
              "clientOrderId": "client",
              "price": "100.0",
              "origQty": "1.0",
              "executedQty": "0.5",
              "cummulativeQuoteQty": "50.0",
              "status": "NEW",
              "timeInForce": "GTC",
              "type": "LIMIT",
              "side": "BUY",
              "time": 1000,
              "transactTime": 2000
            }
        """.trimIndent()

        val dto = json.decodeFromString(OrderDto.serializer(), payload)

        assertEquals("BTCUSDT", dto.symbol)
        assertEquals(1L, dto.orderId)
        assertEquals(1000L, dto.time)
        assertEquals(2000L, dto.transactTime)
    }

    @Test
    fun `deserialize MyTradeDto`() {
        val payload = """
            {
              "symbol": "BTCUSDT",
              "id": 1,
              "orderId": 2,
              "price": "100.0",
              "qty": "0.1",
              "quoteQty": "10.0",
              "commission": "0.01",
              "commissionAsset": "BNB",
              "time": 123456,
              "isBuyer": true,
              "isMaker": false,
              "isBestMatch": true
            }
        """.trimIndent()

        val dto = json.decodeFromString(MyTradeDto.serializer(), payload)

        assertEquals("BTCUSDT", dto.symbol)
        assertEquals(1L, dto.tradeId)
        assertEquals("0.1", dto.quantity)
        assertEquals(true, dto.isBuyer)
    }
}
