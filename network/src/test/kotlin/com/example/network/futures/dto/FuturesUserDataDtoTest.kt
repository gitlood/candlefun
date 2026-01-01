package com.example.network.futures.dto

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FuturesUserDataDtoTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `deserialize order trade update`() {
        val payload = """
            {
              "e": "ORDER_TRADE_UPDATE",
              "E": 123,
              "o": {
                "s": "BTCUSDT",
                "S": "BUY",
                "o": "LIMIT",
                "X": "NEW",
                "p": "100.0",
                "q": "1.0",
                "z": "0.0",
                "l": "0.0",
                "L": "0.0",
                "T": 456
              }
            }
        """.trimIndent()

        val event = json.decodeFromString(FuturesUserDataEvent.serializer(), payload)

        assertTrue(event is FuturesOrderTradeUpdate)
        val order = (event as FuturesOrderTradeUpdate).order
        assertEquals("BTCUSDT", order.symbol)
        assertEquals("LIMIT", order.orderType)
    }

    @Test
    fun `deserialize account update`() {
        val payload = """
            {
              "e": "ACCOUNT_UPDATE",
              "E": 999,
              "a": {
                "B": [
                  {"a": "USDT", "wb": "10", "cw": "9"}
                ],
                "P": [
                  {"s": "BTCUSDT", "pa": "0.1", "ep": "100", "cr": "0", "up": "1"}
                ]
              }
            }
        """.trimIndent()

        val event = json.decodeFromString(FuturesUserDataEvent.serializer(), payload)

        assertTrue(event is FuturesAccountUpdate)
        val account = (event as FuturesAccountUpdate).account
        assertEquals(1, account.balances.size)
        assertEquals("USDT", account.balances[0].asset)
    }

    @Test
    fun `deserialize unknown event falls back to order update`() {
        val payload = """
            {
              "e": "SOMETHING_ELSE",
              "E": 1,
              "o": {
                "s": "BTCUSDT",
                "S": "BUY",
                "o": "LIMIT",
                "X": "NEW",
                "p": "100.0",
                "q": "1.0",
                "z": "0.0",
                "l": "0.0",
                "L": "0.0",
                "T": 2
              }
            }
        """.trimIndent()

        val event = json.decodeFromString(FuturesUserDataEvent.serializer(), payload)

        assertTrue(event is FuturesOrderTradeUpdate)
    }
}
