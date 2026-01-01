package com.example.network.futures

import com.example.network.futures.config.FuturesEndpoints
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class FuturesOrderBookServiceImplTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `getDepth builds params and maps response`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("/fapi/v1/depth", request.url.encodedPath)
            assertEquals("BTCUSDT", request.url.parameters["symbol"])
            assertEquals("50", request.url.parameters["limit"])

            respondJson(
                """
                {
                  "lastUpdateId": 10,
                  "bids": [["100.0", "1.0"]],
                  "asks": [["101.0", "2.0"]]
                }
                """.trimIndent()
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        val service = FuturesOrderBookServiceImpl(
            client = client,
            endpoints = FuturesEndpoints(restBase = "https://example.com/fapi/v1", wsBase = "wss://example.com/stream")
        )

        val result = service.getDepth(symbol = "BTCUSDT", limit = 50)

        assertEquals(10L, result.lastUpdateId)
        assertEquals(100.0, result.bids[0].price, 0.0)
        client.close()
    }

    private fun MockRequestHandleScope.respondJson(payload: String) = respond(
        content = ByteReadChannel(payload),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    )
}
