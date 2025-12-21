package com.example.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class BinanceOrderBookServiceImplTest {
    @Test
    fun getDepth_parsesDepthLevels() = kotlinx.coroutines.runBlocking {
        val payload = """
            {
              "lastUpdateId": 12345,
              "bids": [["100.0","1.5"],["99.0","2.0"]],
              "asks": [["101.0","1.0"],["102.0","3.0"]]
            }
        """.trimIndent()

        val engine = MockEngine {
            respond(
                content = payload,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        val service = BinanceOrderBookServiceImpl(client)
        val depth = service.getDepth("ETHUSDT", limit = 5)

        assertEquals(12345L, depth.lastUpdateId)
        assertEquals(2, depth.bids.size)
        assertEquals(100.0, depth.bids.first().price)
        assertEquals(1.5, depth.bids.first().quantity)
        assertEquals(101.0, depth.asks.first().price)
    }
}
