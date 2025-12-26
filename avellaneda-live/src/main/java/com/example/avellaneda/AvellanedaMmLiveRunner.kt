package com.example.avellaneda

import com.example.account.domain.Price
import com.example.account.domain.Symbol
import com.example.account.impl.config.InventoryWalletConfig
import com.example.account.impl.inventory.CsvInventoryStateRepository
import com.example.account.impl.inventory.CsvWalletStore
import com.example.execution.impl.SimAccountStateRepository
import com.example.execution.impl.SimExecutionGateway
import com.example.marketdata.model.MarketStateConfig
import com.example.marketdata.repository.FuturesMarketStateRepository
import com.example.marketdata.repository.MarketStateRepository
import com.example.network.BinanceUniverse
import com.example.network.di.networkModule
import com.example.network.futures.di.futuresModule
import com.example.platform.model.UniverseConfig
import com.example.marketdata.model.asSymbol
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import com.example.platform.model.MarketState
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration.Companion.milliseconds

object AvellanedaMmLiveRunner {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val source = (System.getenv("MARKETDATA_SOURCE") ?: "SPOT").uppercase()
        val symbolsEnv = System.getenv("SYMBOLS")
        val topN = System.getenv("TOP_N")?.toIntOrNull() ?: 50
        val tickMs = System.getenv("TICK_MS")?.toLongOrNull() ?: 200L
        val depthLevels = System.getenv("DEPTH_LEVELS")?.toIntOrNull() ?: 10
        val depthSpeedMs = System.getenv("DEPTH_SPEED_MS")?.toLongOrNull() ?: 100L
        val snapshotDepth = System.getenv("SNAPSHOT_DEPTH")?.toIntOrNull() ?: 100
        val logEvery = System.getenv("LOG_EVERY_TICKS")?.toLongOrNull() ?: 1_000L
        val pnlEvery = System.getenv("LOG_PNL_EVERY_TICKS")?.toLongOrNull() ?: 2_000L

        println("Avellaneda live (paper) starting...")
        println("Source       : $source")
        println("TopN         : $topN")
        println("TickMs       : $tickMs")
        println("DepthLevels  : $depthLevels")
        println("DepthSpeedMs : $depthSpeedMs")
        println("SnapDepth    : $snapshotDepth")
        println("LogEvery     : $logEvery")
        println("PnlEvery     : $pnlEvery")

        val koinApp = startKoin {
            if (source == "FUTURES") {
                modules(networkModule, futuresModule)
            } else {
                modules(networkModule)
            }
        }
        val koin = koinApp.koin

        try {
            val universe = koin.get<BinanceUniverse>()
            val symbols = resolveSymbols(symbolsEnv, topN, universe)
            println("Symbols (${symbols.size}): ${symbols.joinToString(", ")}")

            val config = MarketStateConfig(
                tick = tickMs.milliseconds,
                depthLevels = depthLevels,
                depthSpeed = depthSpeedMs.milliseconds,
                snapshotDepthLimit = snapshotDepth
            )

            val accountRepo = SimAccountStateRepository()
            val walletConfig = InventoryWalletConfig.default()
            val inventoryRepo = CsvInventoryStateRepository(CsvWalletStore(walletConfig.walletCsvPath), walletConfig)
            val gateway = SimExecutionGateway(accountRepo, inventoryRepo)
            val strategies = symbols.associateWith { symbol ->
                val cfg = AvellanedaMmConfig.default(symbol).copy(
                    orderQty = System.getenv("ORDER_QTY")?.toDoubleOrNull() ?: 0.001,
                    minSpreadPct = System.getenv("MIN_SPREAD_PCT")?.toDoubleOrNull() ?: 0.0005,
                    inventorySkew = System.getenv("INVENTORY_SKEW")?.toDoubleOrNull() ?: 0.01,
                    maxInventory = System.getenv("MAX_INVENTORY")?.toDoubleOrNull() ?: 0.01,
                    priceTick = System.getenv("PRICE_TICK")?.toDoubleOrNull() ?: 0.01,
                    qtyStep = System.getenv("QTY_STEP")?.toDoubleOrNull() ?: 0.0001,
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

            println("Live paper engine running. Press Ctrl+C to stop.")
            var ticks = 0L
            val fillCounts = mutableMapOf<String, Int>()

            val symbolList = symbols.map { it.asSymbol() }
            val flow: Flow<MarketState> = if (source == "FUTURES") {
                val repo = koin.get<FuturesMarketStateRepository>()
                repo.streamMarketState(symbolList, config)
            } else {
                val repo = koin.get<MarketStateRepository>()
                repo.streamMarketState(symbolList, config)
            }

            flow.collect { state ->
                gateway.onMarketState(state)
                strategies[state.symbol]?.onMarketState(state)
                inventoryRepo.applyMarkPrice(
                    Symbol.of(state.symbol),
                    Price.fromDouble(state.midPrice ?: state.lastTradePrice ?: return@collect),
                    state.eventTimeMs ?: state.timestampMs
                )
                ticks++

                val fills = accountRepo.getFills(Symbol.of(state.symbol), sinceTimeMs = null)
                val prevCount = fillCounts[state.symbol] ?: 0
                if (fills.size > prevCount) {
                    val last = fills.lastOrNull()
                    println("fills=${fills.size} lastFill=${last?.symbol?.value} price=${last?.price?.value}")
                    fillCounts[state.symbol] = fills.size
                }

                if (ticks % logEvery == 0L) {
                    println("ticks=$ticks last=${state.symbol} t=${state.timestampMs}")
                }

                if (ticks % pnlEvery == 0L) {
                    logPnlSummary(inventoryRepo)
                }
            }
        } finally {
            stopKoin()
        }
    }

    private suspend fun logPnlSummary(inventoryRepo: CsvInventoryStateRepository) {
        val positions = inventoryRepo.getInventory()
        if (positions.isEmpty()) {
            println("pnl: no positions")
            return
        }
        val realized = positions.sumOf { it.realizedPnl.value.toDouble() }
        val unrealized = positions.sumOf { it.unrealizedPnl.value.toDouble() }
        val top = positions
            .sortedByDescending { kotlin.math.abs(it.unrealizedPnl.value.toDouble()) }
            .take(3)
            .joinToString(", ") { p ->
                "${p.symbol.value}:${"%.4f".format(p.unrealizedPnl.value.toDouble())}"
            }
        println("pnl: realized=${"%.4f".format(realized)} unrealized=${"%.4f".format(unrealized)} top=$top")
    }

    private suspend fun resolveSymbols(
        symbolsEnv: String?,
        topN: Int,
        universe: BinanceUniverse
    ): List<String> {
        if (!symbolsEnv.isNullOrBlank()) {
            return symbolsEnv.split(',')
                .map { it.trim().uppercase() }
                .filter { it.isNotBlank() }
        }
        println("Fetching universe...")
        val config = UniverseConfig(maxSymbols = topN)
        return universe.fetchTopSymbols(config).map { it.symbol }
    }
}
