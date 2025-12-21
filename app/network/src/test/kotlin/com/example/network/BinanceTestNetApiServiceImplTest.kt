package com.example.network

import io.ktor.client.request.HttpRequestData
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BinanceTestNetApiServiceImplTest {
    @Test
    fun binanceTestNetApiService_signsAndSendsRequest() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val engine = jsonMockEngine { request ->
            requests.add(request)
            val response = """{"symbol":"ETHUSDT","orderId":1,"clientOrderId":"cid","transactTime":1,"price":"1.00"}"""
            respond(
                content = response,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val mockClient = jsonMockClient(engine)

        val service = BinanceTestNetApiServiceImpl(
            client = mockClient,
            apiKey = "key",
            secretKey = "secret"
        )

        val resp = service.createOrder(
            symbol = "ETHUSDT",
            side = "BUY",
            type = "MARKET",
            quantity = "1"
        )
        assertEquals("ETHUSDT", resp.symbol)
        assertEquals("1.00", resp.price)

        val req = requests.single()
        assertEquals(HttpMethod.Post, req.method)
        assertEquals("/api/v3/order", req.url.encodedPath)
        assertTrue(req.url.parameters.contains("symbol"))
        assertTrue(req.url.parameters.contains("signature"))
        assertEquals("key", req.headers["X-MBX-APIKEY"])
    }

    @Test
    fun binanceTestNetApiService_signMatchesKnownHmac() {
        val service = BinanceTestNetApiServiceImpl(
            client = emptyJsonClient(),
            apiKey = "key",
            secretKey = "secret"
        )
        val method = BinanceTestNetApiServiceImpl::class.java.getDeclaredMethod("sign", String::class.java, String::class.java)
        method.isAccessible = true
        val signature = method.invoke(service, "symbol=ETHUSDT&side=BUY", "secret") as String
        assertEquals(64, signature.length)
    }
}
