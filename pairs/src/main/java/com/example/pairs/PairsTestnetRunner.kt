package com.example.pairs

import com.example.account.domain.Price
import com.example.account.domain.Qty
import com.example.account.domain.Symbol
import com.example.account.domain.inventory.InventoryFill
import com.example.account.impl.config.InventoryWalletConfig
import com.example.account.impl.inventory.CsvInventoryStateRepository
import com.example.account.impl.inventory.CsvWalletStore
import com.example.account.domain.AccountStateRepository
import com.example.account.domain.Money
import com.example.execution.domain.ExecutionGateway
import com.example.execution.impl.ExecutionPolicy
import com.example.execution.impl.IntentAllocator
import com.example.execution.impl.PortfolioEngine
import com.example.execution.impl.RiskBudgetEnv
import com.example.marketdata.model.MarketStateConfig
import com.example.marketdata.model.asSymbol
import com.example.marketdata.repository.FuturesMarketStateRepository
import com.example.network.di.networkModule
import com.example.network.futures.di.futuresModule
import com.example.network.futures.helper.FuturesUserStreamTelemetry
import com.example.network.futures.interfaces.BinanceFuturesTestNetApiService
import com.example.network.futures.interfaces.FuturesExchangeInfoService
import com.example.network.futures.interfaces.FuturesSymbolFilters
import com.example.network.futures.interfaces.FuturesUserDataService
import com.example.network.futures.interfaces.FuturesWebSocketService
import com.example.platform.model.MarketState
import com.example.platform.report.ExperimentManifest
import com.example.platform.report.ExperimentManifestWriter
import com.example.platform.report.RunSummary
import com.example.platform.report.RunSummaryWriter
import com.example.platform.report.Telemetry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import kotlin.time.Duration.Companion.milliseconds
import com.example.account.impl.di.accountImplModule
import com.example.execution.impl.di.executionImplModule
import java.io.File
import java.math.BigDecimal

object PairsTestnetRunner {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        Telemetry.configureFromEnv("pairs_testnet", defaultEnabled = true)
        val symbolA = System.getenv("SYMBOL_A") ?: "BTCUSDT"
        val symbolB = System.getenv("SYMBOL_B") ?: "ETHUSDT"
        val tickMs = System.getenv("TICK_MS")?.toLongOrNull() ?: 250L
        val depthLevels = System.getenv("DEPTH_LEVELS")?.toIntOrNull() ?: 10
        val depthSpeedMs = System.getenv("DEPTH_SPEED_MS")?.toLongOrNull() ?: 250L
        val snapshotDepth = System.getenv("SNAPSHOT_DEPTH")?.toIntOrNull() ?: 100
        val logEvery = System.getenv("LOG_EVERY_TICKS")?.toLongOrNull() ?: 1_000L
        val kpiEveryMs = System.getenv("LOG_KPI_EVERY_MS")?.toLongOrNull() ?: 60_000L
        val logPnlEveryMs = System.getenv("LOG_PNL_EVERY_MS")?.toLongOrNull() ?: 60_000L
        val leverage = System.getenv("LEVERAGE")?.toIntOrNull() ?: 1
        val fillsPollMs = System.getenv("FILLS_POLL_MS")?.toLongOrNull() ?: 2_000L

        println("Pairs testnet starting...")
        println("Symbols      : $symbolA/$symbolB")
        println("TickMs       : $tickMs")
        println("DepthLevels  : $depthLevels")
        println("DepthSpeedMs : $depthSpeedMs")
        println("SnapDepth    : $snapshotDepth")
        println("Leverage     : $leverage")
        println("FillsPollMs  : $fillsPollMs")

        val koinApp = startKoin {
            modules(networkModule, futuresModule, accountImplModule, executionImplModule)
        }
        val koin = koinApp.koin

        try {
            val api = koin.get<BinanceFuturesTestNetApiService>(named("futuresTestnetApi"))
            val futuresInfo = koin.get<FuturesExchangeInfoService>()
            val futuresFilters = futuresInfo.fetchSymbolFilters()
            if (futuresFilters.isEmpty()) {
                println("No futures symbol filters available; aborting.")
                return@runBlocking
            }
            if (!futuresFilters.containsKey(symbolA) || !futuresFilters.containsKey(symbolB)) {
                println("Symbols not available in futures filters; aborting.")
                return@runBlocking
            }
            listOf(symbolA, symbolB).forEach { symbol ->
                try {
                    api.setLeverage(symbol, leverage)
                } catch (e: Exception) {
                    println("Leverage set failed for $symbol: ${e.message}")
                }
            }

            val repo = koin.get<FuturesMarketStateRepository>()
            val gateway = koin.get<ExecutionGateway>(named("futuresExecution"))
            val accountRepo = koin.get<AccountStateRepository>(named("futuresAccount"))
            val walletConfig = InventoryWalletConfig.default().let { cfg ->
                val autoPersist = System.getenv("WALLET_AUTOPERSIST")?.toBooleanStrictOrNull()
                if (autoPersist == null) cfg else cfg.copy(autoPersist = autoPersist)
            }
            if (System.getenv("RESET_WALLET")?.toBooleanStrictOrNull() == true) {
                File(walletConfig.walletCsvPath).delete()
            }
            val inventoryRepo = CsvInventoryStateRepository(
                CsvWalletStore(walletConfig.walletCsvPath),
                walletConfig
            )
            try {
                accountRepo.getBalances()
                println("Testnet preflight OK: account access verified.")
            } catch (e: Exception) {
                println("Testnet preflight failed: ${e.message}")
                println("Check BINANCE_TESTNET_API_KEY/SECRET and permissions.")
                return@runBlocking
            }

            val config = MarketStateConfig(
                tick = tickMs.milliseconds,
                depthLevels = depthLevels,
                depthSpeed = depthSpeedMs.milliseconds,
                snapshotDepthLimit = snapshotDepth
            )

            val configPairs = configFromEnv(symbolA, symbolB, futuresFilters)
            val kpi = PairsKpiTracker(configPairs)
            val strategy = PairsIntentStrategy(config = configPairs)
            val allocator = IntentAllocator(
                riskBudget = RiskBudgetEnv.fromEnv(
                    defaultTotal = 1e12,
                    defaultShares = mapOf("pairs" to 1.0)
                )
            )
            val policy = ExecutionPolicy(gateway)
            val engine = PortfolioEngine(gateway, allocator, policy, listOf(strategy))
            val telemetryPath = Telemetry.resolveReportPathFromEnv("pairs_testnet", defaultEnabled = true)
            val manifestWriter = ExperimentManifestWriter.fromEnv()
            manifestWriter?.write(
                ExperimentManifest(
                    timestampMs = System.currentTimeMillis(),
                    strategy = "pairs",
                    mode = "testnet",
                    symbols = listOf(symbolA, symbolB),
                    params = mapOf(
                        "ENTRY_Z" to (System.getenv("ENTRY_Z") ?: ""),
                        "EXIT_Z" to (System.getenv("EXIT_Z") ?: ""),
                        "WINDOW_MS" to (System.getenv("WINDOW_MS") ?: ""),
                        "LEVERAGE" to leverage.toString()
                    ).filterValues { it.isNotBlank() },
                    reportPath = telemetryPath,
                    runId = System.getenv("RUN_ID"),
                    notes = System.getenv("RUN_NOTES")
                )
            )

            val fillCounts = mutableMapOf(symbolA to 0, symbolB to 0)
            val lastFillTime = mutableMapOf(symbolA to 0L, symbolB to 0L)
            launch {
                while (true) {
                    pollFillsFutures(
                        symbols = listOf(symbolA, symbolB),
                        api = api,
                        inventoryRepo = inventoryRepo,
                        fillCounts = fillCounts,
                        lastFillTime = lastFillTime,
                        makerFeePct = System.getenv("MAKER_FEE_PCT")?.toDoubleOrNull() ?: 0.0002,
                        takerFeePct = System.getenv("TAKER_FEE_PCT")?.toDoubleOrNull() ?: 0.0004
                    )
                    delay(fillsPollMs)
                }
            }

            var ticks = 0L
            var lastKpiMs = 0L
            var lastPnlMs = 0L
            var lastFillTotal = 0
            val symbolList = listOf(symbolA.asSymbol(), symbolB.asSymbol())
            val userData = koin.get<FuturesUserDataService>()
            val userWs = koin.get<FuturesWebSocketService>()
            FuturesUserStreamTelemetry.start(this, userData, userWs)
            val flow: Flow<MarketState> = repo.streamMarketState(symbolList, config)
            try {
                flow.collect { state ->
                if (state.symbol != symbolA && state.symbol != symbolB) return@collect
                engine.onMarketState(state)
                inventoryRepo.applyMarkPrice(
                    Symbol.of(state.symbol),
                    Price.fromDouble(state.midPrice ?: state.lastTradePrice ?: return@collect),
                    state.eventTimeMs ?: state.timestampMs
                )
                ticks++
                val now = state.eventTimeMs ?: state.timestampMs
                if (ticks % logEvery == 0L) {
                    println("ticks=$ticks last=${state.symbol} t=${state.timestampMs}")
                }
                if (lastKpiMs == 0L) lastKpiMs = now
                if (now - lastKpiMs >= kpiEveryMs) {
                    PairsReport.print(kpi.summary(), "PAIRS TESTNET KPI")
                    lastKpiMs = now
                }
                if (lastPnlMs == 0L) lastPnlMs = now
                if (now - lastPnlMs >= logPnlEveryMs) {
                    logPnlSummary(inventoryRepo, setOf(symbolA, symbolB))
                    logHealthSummary(inventoryRepo, setOf(symbolA, symbolB), fillCounts, lastFillTotal, lastPnlMs, now)
                    lastFillTotal = fillCounts.values.sum()
                    lastPnlMs = now
                }
            }
            } finally {
                val summary = kpi.summary()
                writePairsTestnetSummary(
                    config = configPairs,
                    summary = summary,
                    telemetryPath = telemetryPath,
                    manifestPath = manifestWriter?.path(),
                    mode = "testnet"
                )
            }
        } finally {
            stopKoin()
        }
    }

    private fun configFromEnv(
        symbolA: String,
        symbolB: String,
        filters: Map<String, FuturesSymbolFilters>
    ): PairsConfig {
        val base = PairsConfig(symbolA = symbolA, symbolB = symbolB)
        val filtersA = filters[symbolA]
        val filtersB = filters[symbolB]
        return base.copy(
            windowMs = System.getenv("WINDOW_MS")?.toLongOrNull() ?: base.windowMs,
            minSamples = System.getenv("MIN_SAMPLES")?.toIntOrNull() ?: base.minSamples,
            entryZ = System.getenv("ENTRY_Z")?.toDoubleOrNull() ?: base.entryZ,
            exitZ = System.getenv("EXIT_Z")?.toDoubleOrNull() ?: base.exitZ,
            maxHoldMs = System.getenv("MAX_HOLD_MS")?.toLongOrNull() ?: base.maxHoldMs,
            minCorr = System.getenv("MIN_CORR")?.toDoubleOrNull() ?: base.minCorr,
            maxVol = System.getenv("MAX_VOL")?.toDoubleOrNull() ?: base.maxVol,
            trendCountLimit = System.getenv("TREND_COUNT_LIMIT")?.toIntOrNull() ?: base.trendCountLimit,
            notional = System.getenv("NOTIONAL")?.toDoubleOrNull() ?: base.notional,
            priceTick = System.getenv("PRICE_TICK")?.toDoubleOrNull() ?: base.priceTick,
            qtyStep = System.getenv("QTY_STEP")?.toDoubleOrNull() ?: base.qtyStep,
            priceTickA = filtersA?.tickSize ?: (System.getenv("PRICE_TICK_A")?.toDoubleOrNull() ?: base.priceTick),
            priceTickB = filtersB?.tickSize ?: (System.getenv("PRICE_TICK_B")?.toDoubleOrNull() ?: base.priceTick),
            qtyStepA = filtersA?.stepSize ?: (System.getenv("QTY_STEP_A")?.toDoubleOrNull() ?: base.qtyStep),
            qtyStepB = filtersB?.stepSize ?: (System.getenv("QTY_STEP_B")?.toDoubleOrNull() ?: base.qtyStep),
            minQtyA = filtersA?.minQty,
            minQtyB = filtersB?.minQty,
            minNotionalA = filtersA?.minNotional,
            minNotionalB = filtersB?.minNotional,
            orderTtlMs = System.getenv("ORDER_TTL_MS")?.toLongOrNull() ?: base.orderTtlMs,
            makerFeePct = System.getenv("MAKER_FEE_PCT")?.toDoubleOrNull() ?: base.makerFeePct,
            takerFeePct = System.getenv("TAKER_FEE_PCT")?.toDoubleOrNull() ?: base.takerFeePct,
            tailZ = System.getenv("TAIL_Z")?.toDoubleOrNull() ?: base.tailZ,
            logSignals = System.getenv("LOG_SIGNALS")?.toBooleanStrictOrNull() ?: base.logSignals
        )
    }

    private suspend fun pollFillsFutures(
        symbols: List<String>,
        api: BinanceFuturesTestNetApiService,
        inventoryRepo: CsvInventoryStateRepository,
        fillCounts: MutableMap<String, Int>,
        lastFillTime: MutableMap<String, Long>,
        makerFeePct: Double,
        takerFeePct: Double
    ) {
        for (symbol in symbols) {
            try {
                val startTime = lastFillTime[symbol]?.plus(1)
                val trades = api.getUserTrades(symbol, startTime = startTime)
                val prevTotal = fillCounts[symbol] ?: 0
                if (trades.isNotEmpty()) {
                    val newTotal = prevTotal + trades.size
                    val last = trades.lastOrNull()
                    println("fillsTotal=$newTotal lastFill=${last?.symbol} price=${last?.price}")
                    fillCounts[symbol] = newTotal
                }
                trades.forEach { t ->
                    val feeRate = if (t.maker) makerFeePct else takerFeePct
                    val fee = if (feeRate > 0.0) {
                        Money.fromString(t.quoteQty).let {
                            Money(it.value.multiply(BigDecimal.valueOf(feeRate)))
                        }
                    } else {
                        Money.ZERO
                    }
                    val signedQty = if (t.buyer) {
                        Qty.fromString(t.quantity)
                    } else {
                        -Qty.fromString(t.quantity)
                    }
                    inventoryRepo.applyFill(
                        InventoryFill(
                            symbol = Symbol.of(t.symbol),
                            signedQty = signedQty,
                            price = Price.fromString(t.price),
                            timestampMs = t.time,
                            fee = fee
                        )
                    )
                }
                val maxTime = trades.maxOfOrNull { it.time }
                if (maxTime != null) lastFillTime[symbol] = maxTime
            } catch (_: Exception) {
                // Ignore invalid symbol or permission errors during polling.
            }
        }
    }

    private suspend fun logPnlSummary(
        inventoryRepo: CsvInventoryStateRepository,
        allowedSymbols: Set<String>
    ) {
        val positions = inventoryRepo.getInventory().filter { allowedSymbols.contains(it.symbol.value) }
        if (positions.isEmpty()) {
            println("pnl: no positions")
            return
        }
        val header = String.format(
            "%-10s %12s %12s %12s %12s %12s %8s",
            "SYMBOL",
            "QTY",
            "AVG",
            "UNR_PNL",
            "REAL_PNL",
            "PNL",
            "PNL%"
        )
        val line = "-".repeat(header.length)
        println(line)
        println(header)
        println(line)

        var totalExposure = 0.0
        var totalRealized = 0.0
        var totalUnrealized = 0.0

        positions.sortedBy { it.symbol.value }.forEach { p ->
            val qty = p.quantity.value.toDouble()
            val avg = p.avgPrice.value.toDouble()
            val unrealized = p.unrealizedPnl.value.toDouble()
            val realized = p.realizedPnl.value.toDouble()
            val pnl = realized + unrealized
            val exposure = kotlin.math.abs(qty * avg)
            totalExposure += exposure
            totalRealized += realized
            totalUnrealized += unrealized
            val pnlPct = if (exposure > 0.0) pnl / exposure * 100.0 else 0.0
            println(
                String.format(
                    "%-10s %12.6f %12.6f %12.4f %12.4f %12.4f %7.2f%%",
                    p.symbol.value,
                    qty,
                    avg,
                    unrealized,
                    realized,
                    pnl,
                    pnlPct
                )
            )
        }

        val totalPnl = totalRealized + totalUnrealized
        val totalPct = if (totalExposure > 0.0) totalPnl / totalExposure * 100.0 else 0.0
        println(line)
        println(
            String.format(
                "%-10s %12s %12s %12.4f %12.4f %12.4f %7.2f%%",
                "TOTAL",
                "",
                "",
                totalUnrealized,
                totalRealized,
                totalPnl,
                totalPct
            )
        )
        println(line)
    }

    private suspend fun logHealthSummary(
        inventoryRepo: CsvInventoryStateRepository,
        allowedSymbols: Set<String>,
        fillCounts: Map<String, Int>,
        lastFillTotal: Int,
        lastLogMs: Long,
        nowMs: Long
    ) {
        val elapsedSec = ((nowMs - lastLogMs).coerceAtLeast(1L)) / 1000.0
        val totalFills = fillCounts.values.sum()
        val fillsPerMin = (totalFills - lastFillTotal) * (60.0 / elapsedSec)
        val positions = inventoryRepo.getInventory().filter { allowedSymbols.contains(it.symbol.value) }
        val maxAbsQty =
            positions.maxOfOrNull { kotlin.math.abs(it.quantity.value.toDouble()) } ?: 0.0
        val sumAbsQty = positions.sumOf { kotlin.math.abs(it.quantity.value.toDouble()) }
        println(
            String.format(
                "health: fillsPerMin=%.2f positions=%d maxAbsQty=%.6f sumAbsQty=%.6f",
                fillsPerMin,
                positions.size,
                maxAbsQty,
                sumAbsQty
            )
        )
    }

    private fun writePairsTestnetSummary(
        config: PairsConfig,
        summary: PairsKpiSummary,
        telemetryPath: String?,
        manifestPath: String?,
        mode: String
    ) {
        val configs = mapOf(
            "symbol_a" to config.symbolA,
            "symbol_b" to config.symbolB,
            "window_ms" to config.windowMs.toString(),
            "entry_z" to config.entryZ.toString(),
            "exit_z" to config.exitZ.toString(),
            "leverage" to (System.getenv("LEVERAGE") ?: "")
        ).filterValues { it.isNotBlank() }
        RunSummaryWriter.writeSummary(
            root = findProjectRoot(),
            summary = RunSummary(
                strategy = "pairs",
                mode = mode,
                timestampMs = System.currentTimeMillis(),
                configs = configs,
                metrics = mapOf(
                    "avg_half_life_ms" to summary.avgHalfLifeMs,
                    "tail_events" to summary.tailEvents,
                    "realized_pnl" to summary.realizedPnL,
                    "total_fees" to summary.totalFees,
                    "net_pnl" to summary.netPnL
                ),
                health = emptyMap(),
                notes = mapOfNotNulls(
                    "telemetry_path" to telemetryPath,
                    "manifest_path" to manifestPath,
                    "run_id" to System.getenv("RUN_ID"),
                    "run_notes" to System.getenv("RUN_NOTES")
                )
            )
        )
    }

    private fun mapOfNotNulls(vararg pairs: Pair<String, String?>): Map<String, String> {
        return pairs.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
    }

    private fun findProjectRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        while (true) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            val parent = dir.parentFile ?: return dir
            dir = parent
        }
    }
}
