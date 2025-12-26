package com.example.avellaneda

import com.example.marketdata.repository.FuturesMarketStateRepository
import com.example.network.BinanceUniverse
import com.example.platform.model.BookLevel
import com.example.platform.model.MarketState
import com.example.platform.model.SymbolLiquidity
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.coEvery
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.Ignore

class AvellanedaMmLiveRunnerTest {
    @Ignore
    @Test
    fun `main runs with mocked koin`() {
        mockkStatic("org.koin.core.context.GlobalContextKt")
        mockkObject(GlobalContext)
        mockkStatic(System::class)

        val env = mapOf(
            "MARKETDATA_SOURCE" to "FUTURES",
            "SYMBOLS" to "BTCUSDT",
            "LOG_EVERY_TICKS" to "1",
            "LOG_PNL_EVERY_MS" to "1",
            "WALLET_AUTOPERSIST" to "false"
        )
        every { System.getenv(any<String>()) } answers { env[firstArg<String>()] }

        val koin = mockk<Koin>()
        val koinApp = mockk<KoinApplication>()
        every { koinApp.koin } returns koin
        every { startKoin(any<org.koin.core.KoinApplication.() -> Unit>()) } returns koinApp
        every { stopKoin() } returns Unit
        every { GlobalContext.startKoin(any<org.koin.core.KoinApplication.() -> Unit>()) } returns koinApp
        every { GlobalContext.stopKoin() } returns Unit

        val universe = mockk<BinanceUniverse>()
        coEvery { universe.fetchTopSymbols(any()) } returns listOf(
            SymbolLiquidity(symbol = "BTCUSDT", quoteVolume = 1.0, trades = 1)
        )
        every { koin.get<BinanceUniverse>() } returns universe

        val repo = mockk<FuturesMarketStateRepository>()
        every { repo.streamMarketState(any(), any()) } returns flowOf(
            marketState(symbol = "BTCUSDT", eventTimeMs = 1L),
            marketState(symbol = "BTCUSDT", eventTimeMs = 2L)
        )
        every { koin.get<FuturesMarketStateRepository>() } returns repo

        AvellanedaMmLiveRunner.main(emptyArray())

        unmockkStatic("org.koin.core.context.GlobalContextKt")
        io.mockk.unmockkObject(GlobalContext)
        unmockkStatic(System::class)
    }

    private fun marketState(symbol: String, eventTimeMs: Long): MarketState {
        return MarketState(
            symbol = symbol,
            timestampMs = eventTimeMs,
            eventTimeMs = eventTimeMs,
            bestBidPrice = 99.0,
            bestBidQty = 1.0,
            bestAskPrice = 101.0,
            bestAskQty = 1.0,
            midPrice = 100.0,
            spread = 2.0,
            microPrice = 100.0,
            depthImbalance = 0.0,
            ofi1s = 0.0,
            tradeCount1s = 1,
            tradeVolume1s = 1.0,
            tradeImbalance1s = 0.0,
            lastTradePrice = 100.0,
            lastTradeQty = 0.1,
            lastTradeIsBuyerMaker = true,
            vol1s = 0.1,
            vol5s = 0.2,
            vol10s = 0.3,
            vol1m = 0.4,
            vol5m = 0.5,
            bookUpdateId = 1L,
            bidLevels = listOf(BookLevel(99.0, 1.0)),
            askLevels = listOf(BookLevel(101.0, 1.0))
        )
    }
}
