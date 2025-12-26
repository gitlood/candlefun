package com.example.avellaneda

import com.example.account.domain.AccountStateRepository
import com.example.account.domain.Symbol
import com.example.execution.domain.ExecutionGateway
import com.example.execution.impl.EnvExecutionCredentialsProvider
import com.example.execution.impl.di.executionImplModule
import com.example.account.impl.di.accountImplModule
import com.example.marketdata.model.MarketStateConfig
import com.example.marketdata.repository.FuturesMarketStateRepository
import com.example.marketdata.repository.MarketStateRepository
import com.example.marketdata.model.asSymbol
import com.example.network.BinanceUniverse
import com.example.network.di.networkModule
import com.example.network.futures.di.futuresModule
import com.example.network.futures.interfaces.BinanceFuturesTestNetApiService
import com.example.network.futures.interfaces.FuturesExchangeInfoService
import com.example.platform.model.MarketState
import com.example.platform.model.UniverseConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.koin.core.qualifier.named
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.time.Duration.Companion.milliseconds

object AvellanedaMmTestnetRunner {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val credsProvider = EnvExecutionCredentialsProvider()
        try {
            credsProvider.testnet()
        } catch (e: IllegalArgumentException) {
            println("Missing testnet credentials: ${e.message}")
            println("Set BINANCE_TESTNET_API_KEY / BINANCE_TESTNET_SECRET_KEY (or BINANCE_TEST_KEY / BINANCE_TEST_SECRET).")
            return@runBlocking
        }
        val source = (System.getenv("MARKETDATA_SOURCE") ?: "FUTURES").uppercase()
        val symbolsEnv = System.getenv("SYMBOLS")
        val topN = System.getenv("TOP_N")?.toIntOrNull() ?: 50
        val tickMs = System.getenv("TICK_MS")?.toLongOrNull() ?: 200L
        val depthLevels = System.getenv("DEPTH_LEVELS")?.toIntOrNull() ?: 10
        val depthSpeedMs = System.getenv("DEPTH_SPEED_MS")?.toLongOrNull() ?: 100L
        val snapshotDepth = System.getenv("SNAPSHOT_DEPTH")?.toIntOrNull() ?: 100
        val logEvery = System.getenv("LOG_EVERY_TICKS")?.toLongOrNull() ?: 1_000L
        val fillPollMs = System.getenv("FILL_POLL_MS")?.toLongOrNull() ?: 2_000L

        println("Avellaneda live (testnet execution) starting...")
        println("Source       : $source")
        println("TopN         : $topN")
        println("TickMs       : $tickMs")
        println("DepthLevels  : $depthLevels")
        println("DepthSpeedMs : $depthSpeedMs")
        println("SnapDepth    : $snapshotDepth")
        println("FillPollMs   : $fillPollMs")

        val koinApp = startKoin {
            if (source == "FUTURES") {
                modules(networkModule, futuresModule, accountImplModule, executionImplModule)
            } else {
                modules(networkModule, accountImplModule, executionImplModule)
            }
        }
        val koin = koinApp.koin

        try {
            val universe = koin.get<BinanceUniverse>()
            val futuresInfo = if (source == "FUTURES") koin.get<FuturesExchangeInfoService>() else null
            val futuresFilters = if (source == "FUTURES") futuresInfo?.fetchSymbolFilters() else emptyMap()
            if (source == "FUTURES" && futuresFilters.isNullOrEmpty()) {
                println("No futures symbol filters available; aborting.")
                return@runBlocking
            }
            val symbols = resolveSymbols(source, symbolsEnv, topN, universe, futuresFilters)
            val filteredSymbols = if (source == "FUTURES") {
                val allowed = futuresFilters?.keys ?: emptySet()
                symbols.filter { allowed.contains(it) }
            } else {
                symbols
            }
            if (filteredSymbols.isEmpty()) {
                println("No symbols available after futures filtering; aborting.")
                return@runBlocking
            }
            val leverage = System.getenv("LEVERAGE")?.toIntOrNull() ?: 1
            val liveSymbols = if (source == "FUTURES") {
                val api = koin.get<BinanceFuturesTestNetApiService>()
                val okSymbols = mutableListOf<String>()
                for (symbol in filteredSymbols) {
                    try {
                        api.setLeverage(symbol, leverage)
                        okSymbols.add(symbol)
                    } catch (e: Exception) {
                        println("Leverage set failed for $symbol: ${e.message}")
                    }
                }
                okSymbols
            } else {
                filteredSymbols
            }
            if (liveSymbols.isEmpty()) {
                println("No symbols available after leverage setup; aborting.")
                return@runBlocking
            }
            println("Symbols (${liveSymbols.size}): ${liveSymbols.joinToString(", ")}")

            val config = MarketStateConfig(
                tick = tickMs.milliseconds,
                depthLevels = depthLevels,
                depthSpeed = depthSpeedMs.milliseconds,
                snapshotDepthLimit = snapshotDepth
            )

            val gateway = if (source == "FUTURES") {
                koin.get<ExecutionGateway>(named("futuresExecution"))
            } else {
                koin.get<ExecutionGateway>()
            }
            val accountRepo = if (source == "FUTURES") {
                koin.get<AccountStateRepository>(named("futuresAccount"))
            } else {
                koin.get<AccountStateRepository>()
            }
            try {
                accountRepo.getBalances()
                println("Testnet preflight OK: account access verified.")
            } catch (e: Exception) {
                println("Testnet preflight failed: ${e.message}")
                println("Check BINANCE_TESTNET_API_KEY/SECRET and permissions.")
                return@runBlocking
            }
            val strategies = liveSymbols.associateWith { symbol ->
                val filters = futuresFilters?.get(symbol)
                val priceTick = filters?.tickSize ?: (System.getenv("PRICE_TICK")?.toDoubleOrNull() ?: 0.01)
                val qtyStep = filters?.stepSize ?: (System.getenv("QTY_STEP")?.toDoubleOrNull() ?: 0.0001)
                val minQty = filters?.minQty
                val minNotional = filters?.minNotional
                val baseQty = System.getenv("ORDER_QTY")?.toDoubleOrNull() ?: 0.001
                val orderQty = if (minQty != null && baseQty < minQty) minQty else baseQty
                val cfg = AvellanedaMmConfig.default(symbol).copy(
                    orderQty = orderQty,
                    minSpreadPct = System.getenv("MIN_SPREAD_PCT")?.toDoubleOrNull() ?: 0.0005,
                    minNotional = minNotional,
                    inventorySkew = System.getenv("INVENTORY_SKEW")?.toDoubleOrNull() ?: 0.01,
                    maxInventory = System.getenv("MAX_INVENTORY")?.toDoubleOrNull() ?: 0.01,
                    priceTick = priceTick,
                    qtyStep = qtyStep,
                    quoteRefreshMs = System.getenv("QUOTE_REFRESH_MS")?.toLongOrNull() ?: 500L,
                    maxQuoteAgeMs = System.getenv("MAX_QUOTE_AGE_MS")?.toLongOrNull() ?: 5_000L,
                    maxSpreadPct = System.getenv("MAX_SPREAD_PCT")?.toDoubleOrNull(),
                    minTopDepth = System.getenv("MIN_TOP_DEPTH")?.toDoubleOrNull(),
                    maxDepthImbalance = System.getenv("MAX_DEPTH_IMBALANCE")?.toDoubleOrNull(),
                    maxTradeImbalance1s = System.getenv("MAX_TRADE_IMBALANCE_1S")?.toDoubleOrNull(),
                    minTradeCount1sForToxicity = System.getenv("MIN_TRADE_COUNT_1S")?.toIntOrNull() ?: 5,
                    maxVol1s = System.getenv("MAX_VOL_1S")?.toDoubleOrNull(),
                    maxVol5s = System.getenv("MAX_VOL_5S")?.toDoubleOrNull(),
                    maxVol10s = System.getenv("MAX_VOL_10S")?.toDoubleOrNull(),
                    logGateDecisions = System.getenv("LOG_GATES")?.toBooleanStrictOrNull() ?: false
                )
                AvellanedaMmStrategy(gateway, cfg)
            }

            println("Testnet execution running. Press Ctrl+C to stop.")
            var ticks = 0L
            var lastFillPoll = 0L
            val fillCounts = mutableMapOf<String, Int>()

            val symbolList = liveSymbols.map { it.asSymbol() }
            val flow: Flow<MarketState> = if (source == "FUTURES") {
                val repo = koin.get<FuturesMarketStateRepository>()
                repo.streamMarketState(symbolList, config)
            } else {
                val repo = koin.get<MarketStateRepository>()
                repo.streamMarketState(symbolList, config)
            }

            flow.collect { state ->
                strategies[state.symbol]?.onMarketState(state)
                ticks++

                val now = state.eventTimeMs ?: state.timestampMs
                if (now - lastFillPoll >= fillPollMs) {
                    pollFills(liveSymbols, accountRepo, fillCounts)
                    lastFillPoll = now
                }

                if (ticks % logEvery == 0L) {
                    println("ticks=$ticks last=${state.symbol} t=${state.timestampMs}")
                }
            }
        } finally {
            stopKoin()
        }
    }

    private suspend fun pollFills(
        symbols: List<String>,
        accountRepo: AccountStateRepository,
        fillCounts: MutableMap<String, Int>
    ) {
        for (symbol in symbols) {
            try {
                val fills = accountRepo.getFills(Symbol.of(symbol), sinceTimeMs = null)
                val prev = fillCounts[symbol] ?: 0
                if (fills.size > prev) {
                    val last = fills.lastOrNull()
                    println("fills=${fills.size} lastFill=${last?.symbol?.value} price=${last?.price?.value}")
                    fillCounts[symbol] = fills.size
                }
            } catch (_: Exception) {
                // Ignore invalid symbol or permission errors during polling.
            }
        }
    }

    private suspend fun resolveSymbols(
        source: String,
        symbolsEnv: String?,
        topN: Int,
        universe: BinanceUniverse,
        futuresFilters: Map<String, com.example.network.futures.interfaces.FuturesSymbolFilters>?
    ): List<String> {
        val futuresSymbols = futuresFilters?.keys ?: emptySet()
        if (!symbolsEnv.isNullOrBlank()) {
            return symbolsEnv.split(',')
                .map { it.trim().uppercase() }
                .filter { it.isNotBlank() }
                .filter { symbol -> futuresSymbols.isEmpty() || futuresSymbols.contains(symbol) }
        }
        println("Fetching universe...")
        val config = UniverseConfig(maxSymbols = topN)
        val ranked = universe.fetchTopSymbols(config).map { it.symbol }
        return if (futuresSymbols.isEmpty()) {
            ranked
        } else {
            val filtered = ranked.filter { futuresSymbols.contains(it) }
            if (filtered.isEmpty()) {
                listOf("BTCUSDT", "ETHUSDT")
            } else {
                filtered
            }
        }
    }
}
