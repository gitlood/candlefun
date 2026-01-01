package com.example.avellaneda

import com.example.account.domain.AccountStateRepository
import com.example.account.domain.Price
import com.example.account.domain.Qty
import com.example.execution.domain.ExecutionGateway
import com.example.execution.domain.ExecutionOrder
import com.example.execution.domain.ExecutionCredentials
import com.example.execution.domain.OrderCancelRequest
import com.example.execution.domain.OrderRequest
import com.example.execution.domain.OrderStatus
import com.example.execution.domain.TimeInForce
import com.example.execution.impl.EnvExecutionCredentialsProvider
import com.example.marketdata.repository.FuturesMarketStateRepository
import com.example.network.BinanceUniverse
import com.example.network.futures.dto.FuturesLeverageDto
import com.example.network.futures.interfaces.BinanceFuturesTestNetApiService
import com.example.network.futures.interfaces.FuturesExchangeInfoService
import com.example.network.futures.interfaces.FuturesSymbolFilters
import com.example.platform.model.BookLevel
import com.example.platform.model.MarketState
import com.example.platform.model.SymbolLiquidity
import com.example.platform.model.enums.OrderSide
import com.example.platform.model.enums.OrderType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import kotlin.test.Ignore
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named

class AvellanedaMmTestnetRunnerTest {
    @Ignore("Runner main path can block; covered by helper tests")
    @Test
    fun `main runs with mocked dependencies`() {
        mockkStatic("org.koin.core.context.GlobalContextKt")
        mockkObject(GlobalContext)
        mockkStatic(System::class)
        mockkConstructor(EnvExecutionCredentialsProvider::class)

        val env = mapOf(
            "MARKETDATA_SOURCE" to "FUTURES",
            "SYMBOLS" to "BTCUSDT",
            "FILL_POLL_MS" to "0",
            "LOG_EVERY_TICKS" to "1",
            "CLEAN_START" to "false",
            "RESET_WALLET" to "false",
            "LEVERAGE" to "1",
            "WALLET_AUTOPERSIST" to "false"
        )
        every { System.getenv(any<String>()) } answers { env[firstArg<String>()] }
        every { anyConstructed<EnvExecutionCredentialsProvider>().testnet() } returns ExecutionCredentials(
            apiKey = "key",
            secretKey = "secret"
        )

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

        val exchangeInfo = mockk<FuturesExchangeInfoService>()
        coEvery { exchangeInfo.fetchSymbolFilters() } returns mapOf(
            "BTCUSDT" to FuturesSymbolFilters(
                tickSize = 0.01,
                stepSize = 0.001,
                minQty = 0.001,
                minNotional = 1.0
            )
        )
        every { koin.get<FuturesExchangeInfoService>() } returns exchangeInfo

        val api = mockk<BinanceFuturesTestNetApiService>()
        coEvery { api.setLeverage(any(), any()) } returns FuturesLeverageDto(
            symbol = "BTCUSDT",
            leverage = 1,
            maxNotionalValue = "0"
        )
        coEvery { api.getUserTrades(any(), any(), any(), any()) } returns emptyList()
        every { koin.get<BinanceFuturesTestNetApiService>() } returns api

        val gateway = mockk<ExecutionGateway>()
        coEvery { gateway.getPositions() } returns emptyList()
        coEvery { gateway.getOpenOrders(any()) } returns emptyList()
        coEvery { gateway.placeOrder(any()) } answers { orderFromRequest(firstArg()) }
        coEvery { gateway.cancelOrder(any()) } answers { cancelFromRequest(firstArg()) }
        every { koin.get<ExecutionGateway>(named("futuresExecution")) } returns gateway

        val accountRepo = mockk<AccountStateRepository>()
        coEvery { accountRepo.getBalances() } returns emptyList()
        coEvery { accountRepo.getFills(any(), any()) } returns emptyList()
        every { koin.get<AccountStateRepository>(named("futuresAccount")) } returns accountRepo

        val repo = mockk<FuturesMarketStateRepository>()
        every { repo.streamMarketState(any(), any()) } returns flowOf(
            marketState(symbol = "BTCUSDT", eventTimeMs = 1_000L),
            marketState(symbol = "BTCUSDT", eventTimeMs = 61_000L)
        )
        every { koin.get<FuturesMarketStateRepository>() } returns repo

        AvellanedaMmTestnetRunner.main(emptyArray())

        unmockkStatic("org.koin.core.context.GlobalContextKt")
        io.mockk.unmockkObject(GlobalContext)
        unmockkStatic(System::class)
    }

    private fun orderFromRequest(request: OrderRequest): ExecutionOrder {
        return ExecutionOrder(
            symbol = request.symbol,
            orderId = 1L,
            clientOrderId = request.clientOrderId,
            price = request.price ?: Price.ZERO,
            originalQty = request.quantity,
            executedQty = Qty.ZERO,
            status = OrderStatus.NEW,
            type = request.type,
            side = request.side,
            timeInForce = request.timeInForce,
            transactTimeMs = 1L
        )
    }

    private fun cancelFromRequest(request: OrderCancelRequest): ExecutionOrder {
        return ExecutionOrder(
            symbol = request.symbol,
            orderId = request.orderId ?: 1L,
            clientOrderId = request.clientOrderId,
            price = Price.ZERO,
            originalQty = Qty.ZERO,
            executedQty = Qty.ZERO,
            status = OrderStatus.CANCELED,
            type = OrderType.LIMIT,
            side = OrderSide.BUY,
            timeInForce = TimeInForce.GTC,
            transactTimeMs = 1L
        )
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
