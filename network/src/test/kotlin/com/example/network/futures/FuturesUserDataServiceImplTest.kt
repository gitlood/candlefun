package com.example.network.futures

import com.example.network.futures.config.FuturesEndpoints
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class FuturesUserDataServiceImplTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `createListenKey posts with api key`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/fapi/v1/listenKey", request.url.encodedPath)
            assertEquals("key", request.headers["X-MBX-APIKEY"])

            respondJson("""{"listenKey":"abc"}""")
        }
        val service = FuturesUserDataServiceImpl(
            client = HttpClient(engine) { install(ContentNegotiation) { json(json) } },
            endpoints = FuturesEndpoints(restBase = "https://example.com/fapi/v1", wsBase = "wss://example.com/stream"),
            apiKey = "key"
        )

        val key = service.createListenKey()

        assertEquals("abc", key)
    }

    @Test
    fun `keepAliveListenKey sends put with listenKey`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals("/fapi/v1/listenKey", request.url.encodedPath)
            assertEquals("key", request.headers["X-MBX-APIKEY"])
            assertEquals("abc", request.url.parameters["listenKey"])
            respondTextOk()
        }
        val service = FuturesUserDataServiceImpl(
            client = HttpClient(engine) { install(ContentNegotiation) { json(json) } },
            endpoints = FuturesEndpoints(restBase = "https://example.com/fapi/v1", wsBase = "wss://example.com/stream"),
            apiKey = "key"
        )

        service.keepAliveListenKey("abc")
    }

    @Test
    fun `closeListenKey sends delete with listenKey`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Delete, request.method)
            assertEquals("/fapi/v1/listenKey", request.url.encodedPath)
            assertEquals("key", request.headers["X-MBX-APIKEY"])
            assertEquals("abc", request.url.parameters["listenKey"])
            respondTextOk()
        }
        val service = FuturesUserDataServiceImpl(
            client = HttpClient(engine) { install(ContentNegotiation) { json(json) } },
            endpoints = FuturesEndpoints(restBase = "https://example.com/fapi/v1", wsBase = "wss://example.com/stream"),
            apiKey = "key"
        )

        service.closeListenKey("abc")
    }

    private fun MockRequestHandleScope.respondJson(payload: String) = respond(
        content = ByteReadChannel(payload),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    )

    private fun MockRequestHandleScope.respondTextOk() = respond(
        content = ByteReadChannel(""),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
    )
}
