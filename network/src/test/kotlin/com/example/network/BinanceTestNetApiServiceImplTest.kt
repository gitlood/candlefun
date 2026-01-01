package com.example.network

import com.example.network.config.BinanceEndpoints
import com.example.network.dto.AccountInfoDto
import com.example.network.dto.BalanceDto
import com.example.network.dto.TradeResponseDto
import com.example.network.security.BinanceSigner
import com.example.network.security.TimestampProvider
import com.example.platform.model.enums.OrderSide
import com.example.platform.model.enums.OrderType
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class BinanceTestNetApiServiceImplTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `createOrder sends signed params and maps response`() = runBlocking {
        val apiKey = "test-key"
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/v3/order", request.url.encodedPath)
            assertEquals(apiKey, request.headers["X-MBX-APIKEY"])

            val params = request.url.parameters
            assertEquals("BTCUSDT", params["symbol"])
            assertEquals("BUY", params["side"])
            assertEquals("LIMIT", params["type"])
            assertEquals("1.5", params["quantity"])
            assertEquals("25000.0", params["price"])
            assertEquals("GTC", params["timeInForce"])
            assertNotNull(params["timestamp"])
            assertNotNull(params["recvWindow"])
            assertNotNull(params["signature"])

            respondJson(
                json.encodeToString(
                    TradeResponseDto(
                        symbol = "BTCUSDT",
                        orderId = 1,
                        clientOrderId = "client",
                        transactTime = 123,
                        price = "25000.0",
                        origQty = "1.5",
                        executedQty = "1.0",
                        cummulativeQuoteQty = "25000.0",
                        status = "NEW",
                        timeInForce = "GTC",
                        type = "LIMIT",
                        side = "BUY"
                    )
                )
            )
        }
        val service = BinanceTestNetApiServiceImpl(
            api = BinancePrivateApi(
                client = buildClient(engine),
                endpoints = endpoints(),
                signer = BinanceSigner("secret"),
                apiKey = apiKey,
                timestampProvider = fixedTimestamp()
            )
        )

        val result = service.createOrder(
            symbol = "BTCUSDT",
            side = OrderSide.BUY,
            type = OrderType.LIMIT,
            quantity = "1.5",
            price = "25000.0",
            timeInForce = "GTC"
        )

        assertEquals("BTCUSDT", result.symbol)
        assertEquals(25000.0, result.price, 0.0001)
        assertEquals(1.5, result.origQty, 0.0001)
    }

    @Test
    fun `createOrder omits optional params when null`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("/api/v3/order", request.url.encodedPath)
            val params = request.url.parameters
            assertEquals("BTCUSDT", params["symbol"])
            assertFalse(params.contains("price"))
            assertFalse(params.contains("timeInForce"))

            respondJson(
                json.encodeToString(
                    TradeResponseDto(
                        symbol = "BTCUSDT",
                        orderId = 2,
                        clientOrderId = "client",
                        transactTime = 123,
                        price = "0",
                        origQty = "2.0",
                        executedQty = "0",
                        cummulativeQuoteQty = "0",
                        status = "NEW",
                        timeInForce = "GTC",
                        type = "MARKET",
                        side = "BUY"
                    )
                )
            )
        }
        val service = BinanceTestNetApiServiceImpl(
            api = BinancePrivateApi(
                client = buildClient(engine),
                endpoints = endpoints(),
                signer = BinanceSigner("secret"),
                apiKey = "test-key",
                timestampProvider = fixedTimestamp()
            )
        )

        val result = service.createOrder(
            symbol = "BTCUSDT",
            side = OrderSide.BUY,
            type = OrderType.MARKET,
            quantity = "2.0",
            price = null,
            timeInForce = null
        )

        assertEquals(2.0, result.origQty, 0.0001)
    }

    @Test
    fun `fetchAccountInfo includes api key and signature`() = runBlocking {
        val apiKey = "test-key"
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/api/v3/account", request.url.encodedPath)
            assertEquals(apiKey, request.headers["X-MBX-APIKEY"])

            val params = request.url.parameters
            assertNotNull(params["timestamp"])
            assertNotNull(params["recvWindow"])
            assertNotNull(params["signature"])

            respondJson(
                json.encodeToString(
                    AccountInfoDto(
                        makerCommission = 1,
                        takerCommission = 2,
                        balances = listOf(
                            BalanceDto(asset = "USDT", free = "10.0", locked = "0.5")
                        )
                    )
                )
            )
        }
        val service = BinanceTestNetApiServiceImpl(
            api = BinancePrivateApi(
                client = buildClient(engine),
                endpoints = endpoints(),
                signer = BinanceSigner("secret"),
                apiKey = apiKey,
                timestampProvider = fixedTimestamp()
            )
        )

        val result = service.fetchAccountInfo()

        assertEquals(1, result.makerCommission)
        assertEquals("USDT", result.balances[0].asset)
    }

    private fun buildClient(engine: MockEngine): HttpClient {
        return HttpClient(engine) {
            install(ContentNegotiation) {
                json(json)
            }
        }
    }

    private fun endpoints(): BinanceEndpoints {
        return BinanceEndpoints(
            restBase = "https://example.com/api/v3",
            wsBase = "wss://example.com/ws"
        )
    }

    private fun fixedTimestamp(): TimestampProvider {
        return object : TimestampProvider {
            override fun getTimestamp(): Long = 1_600_000_000_000
        }
    }

    private fun MockRequestHandleScope.respondJson(payload: String) = respond(
        content = ByteReadChannel(payload),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    )
}
