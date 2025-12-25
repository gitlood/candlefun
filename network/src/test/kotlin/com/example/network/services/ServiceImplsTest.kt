package com.example.network.services

import com.example.network.dto.AggTradeDto
import com.example.network.dto.ExchangeInfoDto
import com.example.network.dto.ExchangeSymbolDto
import com.example.network.dto.Ticker24HrDto
import com.example.network.interfaces.BinancePublicApi
import com.example.platform.model.enums.KlineInterval
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceImplsTest {

    @Test
    fun `getTickers24hr maps to domain`() = runBlocking {
        val api = RecordingPublicApi(
            tickers = listOf(
                Ticker24HrDto(
                    symbol = "BTCUSDT",
                    quoteVolume = "100.5",
                    count = 10,
                    lastPrice = "25000.0"
                )
            )
        )
        val service = TickerServiceImpl(api)

        val result = service.getTickers24hr()

        assertEquals(1, api.tickerCalls)
        assertEquals(1, result.size)
        assertEquals("BTCUSDT", result[0].symbol)
        assertEquals(100.5, result[0].quoteVolume, 0.0001)
    }

    @Test
    fun `getAggTrades maps to domain`() = runBlocking {
        val api = RecordingPublicApi(
            trades = listOf(
                AggTradeDto(
                    tradeId = 1,
                    price = "25000.0",
                    quantity = "0.1",
                    timestamp = 123,
                    isBuyerMaker = false
                )
            )
        )
        val service = TradeServiceImpl(api)

        val result = service.getAggTrades(symbol = "BTCUSDT", fromId = 1, limit = 10)

        assertEquals(1, api.tradeCalls)
        assertEquals(1, result.size)
        assertEquals(25000.0, result[0].price, 0.0001)
    }

    @Test
    fun `getExchangeInfo maps to domain`() = runBlocking {
        val api = RecordingPublicApi(
            exchangeInfo = ExchangeInfoDto(
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
        val service = ExchangeInfoServiceImpl(api)

        val result = service.getExchangeInfo()

        assertEquals(1, api.exchangeCalls)
        assertEquals(1, result.symbols.size)
        assertEquals("BTCUSDT", result.symbols[0].symbol)
        assertEquals("TRADING", result.symbols[0].status)
    }

    private class RecordingPublicApi(
        private val tickers: List<Ticker24HrDto> = emptyList(),
        private val trades: List<AggTradeDto> = emptyList(),
        private val exchangeInfo: ExchangeInfoDto = ExchangeInfoDto()
    ) : BinancePublicApi {
        var tickerCalls = 0
        var tradeCalls = 0
        var exchangeCalls = 0

        override suspend fun getTickers24hr(): List<Ticker24HrDto> {
            tickerCalls++
            return tickers
        }

        override suspend fun getAggTrades(symbol: String, fromId: Long?, limit: Int): List<AggTradeDto> {
            tradeCalls++
            return trades
        }

        override suspend fun getExchangeInfo(): ExchangeInfoDto {
            exchangeCalls++
            return exchangeInfo
        }

        override suspend fun getKlines(
            symbol: String,
            interval: KlineInterval,
            limit: Int,
            startTime: Long?,
            endTime: Long?
        ) = error("Not used")

        override suspend fun getDepth(symbol: String, limit: Int) = error("Not used")
    }
}
