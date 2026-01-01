package com.example.network.serializer

import com.example.network.dto.KlineDto
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class KlineSerializerTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `deserialize valid kline array`() {
        val payload = """
            [1000, "1.0", "2.0", "0.5", "1.5", "10", 2000, "20", 5, "3", "4", "0"]
        """.trimIndent()

        val dto = json.decodeFromString(KlineDto.serializer(), payload)

        assertEquals(1000L, dto.openTime)
        assertEquals("1.5", dto.close)
        assertEquals(5, dto.numberOfTrades)
    }

    @Test(expected = SerializationException::class)
    fun `deserialize invalid kline array throws`() {
        val payload = """[1, "1", "2"]"""
        json.decodeFromString(KlineDto.serializer(), payload)
    }
}
