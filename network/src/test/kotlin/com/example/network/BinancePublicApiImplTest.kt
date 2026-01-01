package com.example.network

import com.example.network.config.BinanceEndpoints
import com.example.network.dto.AggTradeDto
import com.example.network.dto.ExchangeInfoDto
import com.example.network.dto.ExchangeSymbolDto
import com.example.network.dto.Ticker24HrDto
import com.example.platform.model.enums.KlineInterval
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BinancePublicApiImplTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `getKlines builds query params and decodes response`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("/api/v3/klines", request.url.encodedPath)
            assertEquals("BTCUSDT", request.url.parameters["symbol"])
            assertEquals("1m", request.url.parameters["interval"])
            assertEquals("500", request.url.parameters["limit"])
            assertEquals("111", request.url.parameters["startTime"])
            assertEquals("222", request.url.parameters["endTime"])

            respondJson(
                """
                [
                  [111, "1.0", "2.0", "0.5", "1.5", "10.0", 222, "20.0", 5, "3.0", "4.0", "0"]
                ]
                """.trimIndent()
            )
        }
        val api = BinancePublicApiImpl(buildClient(engine), endpoints())

        val result = api.getKlines(
            symbol = "BTCUSDT",
            interval = KlineInterval.ONE_MINUTE,
            limit = 500,
            startTime = 111,
            endTime = 222
        )

        assertEquals(1, result.size)
        assertEquals(111L, result[0].openTime)
        assertEquals("1.5", result[0].close)
    }

    @Test
    fun `getAggTrades omits fromId when null`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("/api/v3/aggTrades", request.url.encodedPath)
            assertEquals("BTCUSDT", request.url.parameters["symbol"])
            assertEquals("200", request.url.parameters["limit"])
            assertFalse(request.url.parameters.contains("fromId"))

            val payload = json.encodeToString(
                listOf(
                    AggTradeDto(
                        tradeId = 1,
                        price = "25000.0",
                        quantity = "0.5",
                        timestamp = 123,
                        isBuyerMaker = true
                    )
                )
            )
            respondJson(payload)
        }
        val api = BinancePublicApiImpl(buildClient(engine), endpoints())

        val result = api.getAggTrades(symbol = "BTCUSDT", fromId = null, limit = 200)

        assertEquals(1, result.size)
        assertEquals(1L, result[0].tradeId)
    }

    @Test
    fun `getTickers24hr hits correct path`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("/api/v3/ticker/24hr", request.url.encodedPath)

            val payload = json.encodeToString(
                listOf(
                    Ticker24HrDto(
                        symbol = "BTCUSDT",
                        quoteVolume = "1000.0",
                        count = 12,
                        lastPrice = "25000.0"
                    )
                )
            )
            respondJson(payload)
        }
        val api = BinancePublicApiImpl(buildClient(engine), endpoints())

        val result = api.getTickers24hr()

        assertEquals(1, result.size)
        assertEquals("BTCUSDT", result[0].symbol)
    }

    @Test
    fun `getExchangeInfo hits correct path`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("/api/v3/exchangeInfo", request.url.encodedPath)

            val payload = json.encodeToString(
                ExchangeInfoDto(
                    symbols = listOf(
                        ExchangeSymbolDto(
                            symbol = "BTCUSDT",
                            status = "TRADING",
                            quoteAsset = "USDT",
                            isSpotTradingAllowed = true,
                            permissions = listOf("SPOT")
                        )
                    )
                )
            )
            respondJson(payload)
        }
        val api = BinancePublicApiImpl(buildClient(engine), endpoints())

        val result = api.getExchangeInfo()

        assertEquals(1, result.symbols.size)
        assertEquals("BTCUSDT", result.symbols[0].symbol)
    }

    @Test
    fun `getDepth builds query params and decodes response`() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("/api/v3/depth", request.url.encodedPath)
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
        val api = BinancePublicApiImpl(buildClient(engine), endpoints())

        val result = api.getDepth(symbol = "BTCUSDT", limit = 50)

        assertEquals(10L, result.lastUpdateId)
        assertEquals("100.0", result.bids[0].price)
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

    private fun MockRequestHandleScope.respondJson(payload: String) = respond(
        content = ByteReadChannel(payload),
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    )
}
