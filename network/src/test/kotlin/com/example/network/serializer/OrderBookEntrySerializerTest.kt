package com.example.network.serializer

import com.example.network.dto.OrderBookEntryDto
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class OrderBookEntrySerializerTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `deserialize valid order book entry`() {
        val payload = """["100.0", "1.5"]"""

        val dto = json.decodeFromString(OrderBookEntryDto.serializer(), payload)

        assertEquals("100.0", dto.price)
        assertEquals("1.5", dto.quantity)
    }

    @Test(expected = SerializationException::class)
    fun `deserialize invalid order book entry throws`() {
        val payload = """["100.0"]"""
        json.decodeFromString(OrderBookEntryDto.serializer(), payload)
    }
}
