package com.example.network

import com.example.network.dto.KlineDto
import com.example.network.dto.OrderBookDto
import com.example.network.dto.OrderBookEntryDto
import com.example.network.interfaces.BinancePublicApi
import com.example.platform.model.enums.KlineInterval
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class BinanceServiceImplTest {

    @Test
    fun `getKlines forwards params and maps to domain`() = runBlocking {
        val api = RecordingPublicApi(
            klineResult = listOf(
                KlineDto(
                    openTime = 10,
                    open = "1.0",
                    high = "2.0",
                    low = "0.5",
                    close = "1.5",
                    volume = "10.0",
                    closeTime = 20,
                    quoteAssetVolume = "100.0",
                    numberOfTrades = 5,
                    takerBuyBaseAssetVolume = "3.0",
                    takerBuyQuoteAssetVolume = "4.0",
                    ignore = "0"
                )
            )
        )
        val service = BinanceApiServiceImpl(api)

        val result = service.getKlines(
            symbol = "BTCUSDT",
            interval = KlineInterval.ONE_MINUTE,
            limit = 50,
            startTime = 1,
            endTime = 2
        )

        assertEquals("BTCUSDT", api.lastSymbol)
        assertEquals(KlineInterval.ONE_MINUTE, api.lastInterval)
        assertEquals(50, api.lastLimit)
        assertEquals(1L, api.lastStart)
        assertEquals(2L, api.lastEnd)
        assertEquals(1, result.size)
        assertEquals(1.5, result[0].close, 0.0001)
    }

    @Test
    fun `getDepth maps order book`() = runBlocking {
        val api = RecordingPublicApi(
            depthResult = OrderBookDto(
                lastUpdateId = 123,
                bids = listOf(OrderBookEntryDto(price = "100.0", quantity = "1.0")),
                asks = listOf(OrderBookEntryDto(price = "101.0", quantity = "2.0"))
            )
        )
        val service = BinanceOrderBookServiceImpl(api)

        val result = service.getDepth(symbol = "BTCUSDT", limit = 100)

        assertEquals("BTCUSDT", api.lastDepthSymbol)
        assertEquals(100, api.lastDepthLimit)
        assertEquals(123L, result.lastUpdateId)
        assertEquals(100.0, result.bids[0].price, 0.0001)
    }

    private class RecordingPublicApi(
        private val klineResult: List<KlineDto> = emptyList(),
        private val depthResult: OrderBookDto = OrderBookDto(0, emptyList(), emptyList())
    ) : BinancePublicApi {
        var lastSymbol: String? = null
        var lastInterval: KlineInterval? = null
        var lastLimit: Int? = null
        var lastStart: Long? = null
        var lastEnd: Long? = null
        var lastDepthSymbol: String? = null
        var lastDepthLimit: Int? = null

        override suspend fun getKlines(
            symbol: String,
            interval: KlineInterval,
            limit: Int,
            startTime: Long?,
            endTime: Long?
        ): List<KlineDto> {
            lastSymbol = symbol
            lastInterval = interval
            lastLimit = limit
            lastStart = startTime
            lastEnd = endTime
            return klineResult
        }

        override suspend fun getTickers24hr() = error("Not used")

        override suspend fun getAggTrades(symbol: String, fromId: Long?, limit: Int) = error("Not used")

        override suspend fun getExchangeInfo() = error("Not used")

        override suspend fun getDepth(symbol: String, limit: Int): OrderBookDto {
            lastDepthSymbol = symbol
            lastDepthLimit = limit
            return depthResult
        }
    }
}
