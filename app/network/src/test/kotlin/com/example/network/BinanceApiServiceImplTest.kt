package com.example.network

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class BinanceApiServiceImplTest {
    @Test
    fun binanceApiService_parsesKlines() = runBlocking {
        val engine = jsonMockEngine {
            val payload = """
                [[1,"1.0","2.0","0.5","1.5","10.0",2,"20.0",3,"5.0","7.0","0"]]
            """.trimIndent()
            respond(
                content = payload,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val mockClient = jsonMockClient(engine)

        val service = BinanceApiServiceImpl(mockClient)
        val klines = service.getKlines(symbol = "ETHUSDT", interval = "5m", limit = 1)
        assertEquals(1, klines.size)
        assertEquals(1L, klines.first().openTime)
        assertEquals("1.5", klines.first().close)
    }
}
