package com.example.network.helper

import com.example.platform.model.ApiResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpClientExtensionsTest {

    @Test
    fun `getOrThrow returns body`() = runBlocking {
        val client = HttpClient(
            MockEngine { request ->
                assertEquals(HttpMethod.Get, request.method)
                respond(
                    content = "ok",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                )
            }
        )

        val result = client.getOrThrow<String>("https://example.com")

        assertEquals("ok", result)
        client.close()
    }

    @Test
    fun `postOrThrow returns body`() = runBlocking {
        val client = HttpClient(
            MockEngine { request ->
                assertEquals(HttpMethod.Post, request.method)
                respond(
                    content = "created",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                )
            }
        )

        val result = client.postOrThrow<String>("https://example.com")

        assertEquals("created", result)
        client.close()
    }

    @Test
    fun `getOrResult returns ok for success`() = runBlocking {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = "payload",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                )
            }
        )

        val result = client.getOrResult<String>("https://example.com")

        assertTrue(result is ApiResult.Ok)
        assertEquals("payload", (result as ApiResult.Ok).value)
        client.close()
    }

    @Test
    fun `getOrResult returns err for non 2xx`() = runBlocking {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = "not found",
                    status = HttpStatusCode.NotFound,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString())
                )
            }
        )

        val result = client.getOrResult<String>("https://example.com")

        assertTrue(result is ApiResult.Err)
        val err = result as ApiResult.Err
        assertEquals(404, err.code)
        assertTrue(err.message.contains("not found"))
        client.close()
    }

    @Test
    fun `postOrResult returns err for exception`() = runBlocking {
        val client = HttpClient(
            MockEngine {
                throw RuntimeException("boom")
            }
        )

        val result = client.postOrResult<String>("https://example.com")

        assertTrue(result is ApiResult.Err)
        assertTrue((result as ApiResult.Err).message.contains("boom"))
        client.close()
    }
}
