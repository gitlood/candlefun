package com.example.avellaneda

import com.example.account.domain.AccountStateRepository
import com.example.account.domain.Price
import com.example.account.domain.Symbol
import com.example.account.domain.inventory.InventoryFill
import com.example.account.impl.config.InventoryWalletConfig
import com.example.account.impl.di.accountImplModule
import com.example.account.impl.inventory.CsvInventoryStateRepository
import com.example.account.impl.inventory.CsvWalletStore
import com.example.avellaneda.metrics.AdverseSelectionTracker
import com.example.avellaneda.metrics.FillStats
import com.example.avellaneda.report.AvellanedaCsvReporter
import com.example.avellaneda.report.AvellanedaReportRow
import com.example.execution.domain.ExecutionGateway
import com.example.execution.domain.RiskBudget
import com.example.execution.impl.EnvExecutionCredentialsProvider
import com.example.execution.impl.ExecutionPolicy
import com.example.execution.impl.IntentAllocator
import com.example.execution.impl.PortfolioEngine
import com.example.execution.impl.di.executionImplModule
import com.example.marketdata.model.MarketStateConfig
import com.example.marketdata.model.asSymbol
import com.example.marketdata.repository.FuturesMarketStateRepository
import com.example.marketdata.repository.MarketStateRepository
import com.example.network.BinanceUniverse
import com.example.network.di.networkModule
import com.example.network.futures.di.futuresModule
import com.example.network.futures.helper.FuturesUserStreamTelemetry
import com.example.network.futures.interfaces.BinanceFuturesTestNetApiService
import com.example.network.futures.interfaces.FuturesExchangeInfoService
import com.example.network.futures.interfaces.FuturesUserDataService
import com.example.network.futures.interfaces.FuturesWebSocketService
import com.example.platform.model.MarketState
import com.example.platform.model.UniverseConfig
import com.example.platform.report.ExperimentManifest
import com.example.platform.report.ExperimentManifestWriter
import com.example.platform.report.GistUploader
import com.example.platform.report.HealthSummary
import com.example.platform.report.RunSummary
import com.example.platform.report.RunSummaryWriter
import com.example.platform.report.Telemetry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import java.io.File
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

object AvellanedaMmTestnetRunner {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        Telemetry.configureFromEnv("avellaneda_testnet", defaultEnabled = true)
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
        val makerFeePct = System.getenv("MAKER_FEE_PCT")?.toDoubleOrNull() ?: 0.0002
        val takerFeePct = System.getenv("TAKER_FEE_PCT")?.toDoubleOrNull() ?: 0.0004
        val reportEveryMs = System.getenv("REPORT_EVERY_MS")?.toLongOrNull() ?: 60_000L
        val cleanStart = System.getenv("CLEAN_START")?.toBooleanStrictOrNull() ?: true
        val resetWallet = System.getenv("RESET_WALLET")?.toBooleanStrictOrNull() ?: true

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
            val futuresInfo =
                if (source == "FUTURES") koin.get<FuturesExchangeInfoService>() else null
            val futuresFilters =
                if (source == "FUTURES") futuresInfo?.fetchSymbolFilters() else emptyMap()
            if (source == "FUTURES" && futuresFilters.isNullOrEmpty()) {
                println("No futures symbol filters available; aborting.")
                return@runBlocking
            }
            val minQuoteVolume = System.getenv("MIN_QUOTE_VOLUME")?.toDoubleOrNull() ?: 0.0
            val minTrades = System.getenv("MIN_TRADES")?.toLongOrNull() ?: 0L
            val quoteAssets = System.getenv("QUOTE_ASSETS")
                ?.split(',')
                ?.map { it.trim().uppercase() }
                ?.filter { it.isNotBlank() }
                ?.toSet()
                ?: setOf("USDT")
            val symbols = resolveSymbols(
                source,
                symbolsEnv,
                topN,
                universe,
                futuresFilters,
                minQuoteVolume,
                minTrades,
                quoteAssets
            )
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
            val walletConfig = InventoryWalletConfig.default().let { cfg ->
                val autoPersist = System.getenv("WALLET_AUTOPERSIST")?.toBooleanStrictOrNull()
                if (autoPersist == null) cfg else cfg.copy(autoPersist = autoPersist)
            }
            if (resetWallet) {
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

            if (source == "FUTURES" && cleanStart) {
                val api = koin.get<BinanceFuturesTestNetApiService>()
                cleanupFuturesPositionsAndOrders(
                    api = api,
                    symbols = liveSymbols,
                    filters = futuresFilters ?: emptyMap()
                )
            }
            val adverseTracker = AdverseSelectionTracker()
            val reporter = AvellanedaCsvReporter.fromEnv("testnet", adverseTracker.horizonsLabel())
            if (reporter != null) {
                println("ReportPath   : ${reporter.reportPath()}")
                println("ReportEveryMs: $reportEveryMs")
            }
            val telemetryPath =
                Telemetry.resolveReportPathFromEnv("avellaneda_testnet", defaultEnabled = true)
            val manifestWriter = ExperimentManifestWriter.fromEnv()
            val adverseProvider: (String) -> Double? = { symbol ->
                val adv = adverseTracker.snapshotBps(symbol)
                adv.getOrNull(1) ?: adv.lastOrNull()
            }
            val strategies = liveSymbols.map { symbol ->
                val filters = futuresFilters?.get(symbol)
                val priceTick =
                    filters?.tickSize ?: (System.getenv("PRICE_TICK")?.toDoubleOrNull() ?: 0.01)
                val qtyStep =
                    filters?.stepSize ?: (System.getenv("QTY_STEP")?.toDoubleOrNull() ?: 0.0001)
                val minQty = filters?.minQty
                val minNotional = filters?.minNotional
                val baseQty = System.getenv("ORDER_QTY")?.toDoubleOrNull() ?: 0.001
                val orderQty = if (minQty != null && baseQty < minQty) minQty else baseQty
                val base = AvellanedaMmConfig.default(symbol)
                val cfg = base.copy(
                    orderQty = orderQty,
                    minSpreadPct = System.getenv("MIN_SPREAD_PCT")?.toDoubleOrNull()
                        ?: base.minSpreadPct,
                    minNotional = minNotional,
                    inventorySkew = System.getenv("INVENTORY_SKEW")?.toDoubleOrNull()
                        ?: base.inventorySkew,
                    maxInventory = System.getenv("MAX_INVENTORY")?.toDoubleOrNull()
                        ?: base.maxInventory,
                    priceTick = priceTick,
                    qtyStep = qtyStep,
                    quoteRefreshMs = System.getenv("QUOTE_REFRESH_MS")?.toLongOrNull()
                        ?: base.quoteRefreshMs,
                    maxQuoteAgeMs = System.getenv("MAX_QUOTE_AGE_MS")?.toLongOrNull()
                        ?: base.maxQuoteAgeMs,
                    gateCooldownMs = System.getenv("GATE_COOLDOWN_MS")?.toLongOrNull()
                        ?: base.gateCooldownMs,
                    spreadWindowMs = System.getenv("SPREAD_WINDOW_MS")?.toLongOrNull()
                        ?: base.spreadWindowMs,
                    minAvgSpreadPct = System.getenv("MIN_AVG_SPREAD_PCT")?.toDoubleOrNull()
                        ?: base.minAvgSpreadPct,
                    maxAvgSpreadPct = System.getenv("MAX_AVG_SPREAD_PCT")?.toDoubleOrNull()
                        ?: base.maxAvgSpreadPct,
                    maxSpreadPct = System.getenv("MAX_SPREAD_PCT")?.toDoubleOrNull()
                        ?: base.maxSpreadPct,
                    minTopDepth = System.getenv("MIN_TOP_DEPTH")?.toDoubleOrNull()
                        ?: base.minTopDepth,
                    topDepthLevels = System.getenv("TOP_DEPTH_LEVELS")?.toIntOrNull()
                        ?: base.topDepthLevels,
                    maxDepthImbalance = System.getenv("MAX_DEPTH_IMBALANCE")?.toDoubleOrNull()
                        ?: base.maxDepthImbalance,
                    maxTradeImbalance1s = System.getenv("MAX_TRADE_IMBALANCE_1S")?.toDoubleOrNull()
                        ?: base.maxTradeImbalance1s,
                    minTradeCount1sForToxicity = System.getenv("MIN_TRADE_COUNT_1S")?.toIntOrNull()
                        ?: base.minTradeCount1sForToxicity,
                    maxVol1s = System.getenv("MAX_VOL_1S")?.toDoubleOrNull() ?: base.maxVol1s,
                    maxVol5s = System.getenv("MAX_VOL_5S")?.toDoubleOrNull() ?: base.maxVol5s,
                    maxVol10s = System.getenv("MAX_VOL_10S")?.toDoubleOrNull() ?: base.maxVol10s,
                    volSpreadMultiplier = System.getenv("VOL_SPREAD_MULT")?.toDoubleOrNull()
                        ?: base.volSpreadMultiplier,
                    adaptiveSpreadTargetBps = System.getenv("ADAPTIVE_SPREAD_TARGET_BPS")
                        ?.toDoubleOrNull()
                        ?: base.adaptiveSpreadTargetBps,
                    adaptiveSpreadUpdateMs = System.getenv("ADAPTIVE_SPREAD_UPDATE_MS")
                        ?.toLongOrNull()
                        ?: base.adaptiveSpreadUpdateMs,
                    quoteStyle = parseQuoteStyle(System.getenv("QUOTE_STYLE")),
                    logGateDecisions = System.getenv("LOG_GATES")?.toBooleanStrictOrNull()
                        ?: base.logGateDecisions,
                    makerFeePct = System.getenv("MAKER_FEE_PCT")?.toDoubleOrNull()
                        ?: base.makerFeePct
                )
                if (System.getenv("LOG_CONFIG")?.toBooleanStrictOrNull() == true) {
                    println("config[$symbol]=$cfg")
                }
                AvellanedaMmIntentStrategy(config = cfg, adverseBpsProvider = adverseProvider)
            }
            val allocator = IntentAllocator(riskBudget = RiskBudget(total = 1e12))
            val policy = ExecutionPolicy(gateway)
            val engine = PortfolioEngine(gateway, allocator, policy, strategies)
            val reportPath = telemetryPath ?: reporter?.reportPath()
            manifestWriter?.write(
                ExperimentManifest(
                    timestampMs = System.currentTimeMillis(),
                    strategy = "avellaneda",
                    mode = "testnet",
                    symbols = liveSymbols,
                    params = mapOf(
                        "MARKETDATA_SOURCE" to source,
                        "QUOTE_STYLE" to parseQuoteStyle(System.getenv("QUOTE_STYLE")).name,
                        "ORDER_QTY" to (System.getenv("ORDER_QTY") ?: ""),
                        "MAX_INVENTORY" to (System.getenv("MAX_INVENTORY") ?: ""),
                        "MIN_AVG_SPREAD_PCT" to (System.getenv("MIN_AVG_SPREAD_PCT") ?: ""),
                        "MAX_AVG_SPREAD_PCT" to (System.getenv("MAX_AVG_SPREAD_PCT") ?: ""),
                        "ADAPTIVE_SPREAD_TARGET_BPS" to (System.getenv("ADAPTIVE_SPREAD_TARGET_BPS")
                            ?: ""),
                        "ADAPTIVE_SPREAD_UPDATE_MS" to (System.getenv("ADAPTIVE_SPREAD_UPDATE_MS")
                            ?: ""),
                        "MAKER_FEE_PCT" to makerFeePct.toString(),
                        "TAKER_FEE_PCT" to takerFeePct.toString(),
                        "LEVERAGE" to (System.getenv("LEVERAGE") ?: "")
                    ).filterValues { it.isNotBlank() },
                    reportPath = reportPath,
                    runId = System.getenv("RUN_ID"),
                    notes = System.getenv("RUN_NOTES")
                )
            )
            GistUploader.installUploadOnShutdown(
                label = "avellaneda_testnet",
                files = listOfNotNull(
                    telemetryPath?.let { File(it) },
                    reporter?.reportPath()?.let { File(it) },
                    manifestWriter?.path()?.let { File(it) }
                )
            )

            println("Testnet execution running. Press Ctrl+C to stop.")
            var ticks = 0L
            var lastFillPoll = 0L
            val fillCounts = mutableMapOf<String, Int>()
            val lastFillTime = mutableMapOf<String, Long>()
            var lastPnlLog = 0L
            var lastFillTotal = 0
            var lastReportLog = 0L
            val fillStats = FillStats()
            val latestMid = mutableMapOf<String, Double>()

            val symbolList = liveSymbols.map { it.asSymbol() }
            val flow: Flow<MarketState> = if (source == "FUTURES") {
                val repo = koin.get<FuturesMarketStateRepository>()
                val userData = koin.get<FuturesUserDataService>()
                val userWs = koin.get<FuturesWebSocketService>()
                FuturesUserStreamTelemetry.start(this, userData, userWs)
                repo.streamMarketState(symbolList, config)
            } else {
                val repo = koin.get<MarketStateRepository>()
                repo.streamMarketState(symbolList, config)
            }

            try {
                try {
                    flow.collect { state ->
                        engine.onMarketState(state)
                        adverseTracker.onMarketState(state)
                        val mid = state.midPrice ?: state.lastTradePrice
                        if (mid != null) {
                            latestMid[state.symbol] = mid
                        }
                        ticks++

                        val now = state.eventTimeMs ?: state.timestampMs
                        if (now - lastFillPoll >= fillPollMs) {
                            if (source == "FUTURES") {
                                val api = koin.get<BinanceFuturesTestNetApiService>()
                                pollFillsFutures(
                                    liveSymbols,
                                    api,
                                    inventoryRepo,
                                    fillCounts,
                                    lastFillTime,
                                    makerFeePct,
                                    takerFeePct,
                                    adverseTracker,
                                    fillStats
                                )
                            } else {
                                pollFills(liveSymbols, accountRepo, fillCounts)
                            }
                            lastFillPoll = now
                        }

                        inventoryRepo.applyMarkPrice(
                            Symbol.of(state.symbol),
                            Price.fromDouble(mid ?: return@collect),
                            now
                        )
                        if (lastPnlLog == 0L) lastPnlLog = now
                        if (lastReportLog == 0L) lastReportLog = now
                        if (now - lastPnlLog >= 60_000L) {
                            val allowed = liveSymbols.toSet()
                            logPnlSummary(inventoryRepo, allowed)
                            logHealthSummary(
                                inventoryRepo,
                                allowed,
                                fillCounts,
                                lastFillTotal,
                                lastPnlLog,
                                now,
                                adverseTracker,
                                fillStats
                            )
                            lastFillTotal = fillCounts.values.sum()
                            lastPnlLog = now
                        }
                        if (reporter != null && now - lastReportLog >= reportEveryMs) {
                            val rows = buildReportRows(
                                liveSymbols,
                                inventoryRepo,
                                latestMid,
                                adverseTracker,
                                fillCounts,
                                fillStats,
                                now
                            )
                            reporter.write(rows)
                            lastReportLog = now
                        }

                        if (ticks % logEvery == 0L) {
                            println("ticks=$ticks last=${state.symbol} t=${state.timestampMs}")
                        }
                    }
                } finally {
                    writeAvellanedaTestnetSummary(
                        liveSymbols = liveSymbols,
                        inventoryRepo = inventoryRepo,
                        fillStats = fillStats,
                        fillCounts = fillCounts,
                        adverseTracker = adverseTracker,
                        reporterPath = reporter?.reportPath(),
                        telemetryPath = telemetryPath,
                        manifestPath = manifestWriter?.path(),
                        configs = mapOf(
                            "MARKETDATA_SOURCE" to source,
                            "ORDER_QTY" to (System.getenv("ORDER_QTY") ?: ""),
                            "MAX_INVENTORY" to (System.getenv("MAX_INVENTORY") ?: ""),
                            "MIN_AVG_SPREAD_PCT" to (System.getenv("MIN_AVG_SPREAD_PCT") ?: ""),
                            "MAX_AVG_SPREAD_PCT" to (System.getenv("MAX_AVG_SPREAD_PCT") ?: ""),
                            "ADAPTIVE_SPREAD_TARGET_BPS" to (System.getenv("ADAPTIVE_SPREAD_TARGET_BPS")
                                ?: ""),
                            "ADAPTIVE_SPREAD_UPDATE_MS" to (System.getenv("ADAPTIVE_SPREAD_UPDATE_MS")
                                ?: ""),
                            "MAKER_FEE_PCT" to makerFeePct.toString(),
                            "TAKER_FEE_PCT" to takerFeePct.toString(),
                            "LEVERAGE" to (System.getenv("LEVERAGE") ?: "")
                        )
                    )
                }
            } finally {
                stopKoin()
            }
        } catch (e: Exception) {

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

    private suspend fun writeAvellanedaTestnetSummary(
        liveSymbols: List<String>,
        inventoryRepo: CsvInventoryStateRepository,
        fillStats: FillStats,
        fillCounts: Map<String, Int>,
        adverseTracker: AdverseSelectionTracker,
        reporterPath: String?,
        telemetryPath: String?,
        manifestPath: String?,
        configs: Map<String, String>
    ) {
        val positions = inventoryRepo.getInventory()
        val totalRealized = positions.sumOf { it.realizedPnl.value.toDouble() }
        val totalUnrealized = positions.sumOf { it.unrealizedPnl.value.toDouble() }
        val net = totalRealized + totalUnrealized
        val exposure =
            positions.sumOf { abs(it.quantity.value.toDouble() * it.avgPrice.value.toDouble()) }
        val avgAdv =
            positions.mapNotNull { adverseTracker.snapshotBps(it.symbol.value).firstOrNull() }
                .let { if (it.isEmpty()) null else it.average() }
        RunSummaryWriter.writeSummary(
            root = findProjectRoot(),
            summary = RunSummary(
                strategy = "avellaneda",
                mode = "testnet",
                timestampMs = System.currentTimeMillis(),
                configs = configs.filterValues { it.isNotBlank() },
                metrics = mapOf(
                    "net" to net,
                    "realized" to totalRealized,
                    "unrealized" to totalUnrealized,
                    "exposure" to exposure,
                    "maker_fills" to fillStats.makerCount,
                    "taker_fills" to fillStats.takerCount,
                    "fills_total" to fillCounts.values.sum(),
                    "total_fees" to fillStats.totalFees,
                    "total_notional" to fillStats.totalNotional,
                    "avg_adv_bps" to avgAdv,
                    "symbols" to liveSymbols
                ),
                health = mapOf(
                    "positions" to positions.size,
                    "sum_abs_qty" to positions.sumOf { abs(it.quantity.value.toDouble()) },
                    "max_abs_qty" to positions.maxOfOrNull { abs(it.quantity.value.toDouble()) }
                ),
                notes = mapOfNotNulls(
                    "reporter_path" to reporterPath,
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

    private suspend fun pollFillsFutures(
        symbols: List<String>,
        api: BinanceFuturesTestNetApiService,
        inventoryRepo: CsvInventoryStateRepository,
        fillCounts: MutableMap<String, Int>,
        lastFillTime: MutableMap<String, Long>,
        makerFeePct: Double,
        takerFeePct: Double,
        adverseTracker: AdverseSelectionTracker,
        fillStats: FillStats
    ) {
        for (symbol in symbols) {
            try {
                val startTime = lastFillTime[symbol]?.plus(1)
                val trades = api.getUserTrades(symbol, startTime = startTime)
                val prevTotal = fillCounts[symbol] ?: 0
                if (trades.isNotEmpty()) {
                    val last = trades.lastOrNull()
                    val newTotal = prevTotal + trades.size
                    println("fillsTotal=$newTotal lastFill=${last?.symbol} price=${last?.price}")
                    fillCounts[symbol] = newTotal
                }
                trades.forEach { t ->
                    val feeRate = if (t.maker) makerFeePct else takerFeePct
                    val fee = if (feeRate > 0.0) {
                        com.example.account.domain.Money.fromString(t.quoteQty)
                            .let {
                                com.example.account.domain.Money(
                                    it.value.multiply(
                                        java.math.BigDecimal.valueOf(
                                            feeRate
                                        )
                                    )
                                )
                            }
                    } else {
                        com.example.account.domain.Money.ZERO
                    }
                    val side = if (t.buyer) com.example.platform.model.enums.OrderSide.BUY
                    else com.example.platform.model.enums.OrderSide.SELL
                    val signedQty = if (t.buyer) {
                        com.example.account.domain.Qty.fromString(t.quantity)
                    } else {
                        -com.example.account.domain.Qty.fromString(t.quantity)
                    }
                    inventoryRepo.applyFill(
                        InventoryFill(
                            symbol = Symbol.of(t.symbol),
                            signedQty = signedQty,
                            price = com.example.account.domain.Price.fromString(t.price),
                            timestampMs = t.time,
                            fee = fee
                        )
                    )
                    val notional = t.price.toDoubleOrNull()?.let { p ->
                        val q = t.quantity.toDoubleOrNull() ?: return@forEach
                        p * q
                    } ?: return@forEach
                    fillStats.record(notional, makerFeePct, takerFeePct, isMaker = t.maker)
                    adverseTracker.recordFill(
                        t.symbol,
                        side,
                        t.price.toDoubleOrNull() ?: return@forEach,
                        t.time
                    )
                }
                val maxTime = trades.maxOfOrNull { it.time }
                if (maxTime != null) lastFillTime[symbol] = maxTime
            } catch (_: Exception) {
                // Ignore invalid symbol or permission errors during polling.
            }
        }
    }

    private suspend fun cleanupFuturesPositionsAndOrders(
        api: BinanceFuturesTestNetApiService,
        symbols: List<String>,
        filters: Map<String, com.example.network.futures.interfaces.FuturesSymbolFilters>
    ) {
        println("Cleaning futures testnet: cancel open orders + close positions...")
        symbols.forEach { symbol ->
            runCatching { api.cancelAllOpenOrders(symbol) }
        }
        val account = api.getAccountInfo()
        account.positions.forEach { pos ->
            val qty = pos.positionAmt.toDoubleOrNull() ?: 0.0
            if (qty == 0.0) return@forEach
            val side = if (qty > 0.0) com.example.platform.model.enums.OrderSide.SELL
            else com.example.platform.model.enums.OrderSide.BUY
            val step = filters[pos.symbol]?.stepSize ?: 0.0
            val qtyStr = formatToStep(kotlin.math.abs(qty), step)
            runCatching {
                api.createOrder(
                    symbol = pos.symbol,
                    side = side,
                    type = com.example.platform.model.enums.OrderType.MARKET,
                    quantity = qtyStr,
                    price = null,
                    timeInForce = null,
                    reduceOnly = true
                )
            }
        }
        println("Cleanup done.")
    }

    private fun formatToStep(value: Double, step: Double): String {
        if (step <= 0.0) return java.math.BigDecimal.valueOf(value).toPlainString()
        val stepBd = java.math.BigDecimal.valueOf(step).stripTrailingZeros()
        val scale = stepBd.scale().coerceAtLeast(0)
        val units =
            java.math.BigDecimal.valueOf(value).divide(stepBd, 0, java.math.RoundingMode.DOWN)
        val rounded = units.multiply(stepBd).setScale(scale, java.math.RoundingMode.DOWN)
        return rounded.stripTrailingZeros().toPlainString()
    }

    private suspend fun logPnlSummary(
        inventoryRepo: CsvInventoryStateRepository,
        allowedSymbols: Set<String>
    ) {
        val positions =
            inventoryRepo.getInventory().filter { allowedSymbols.contains(it.symbol.value) }
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
        nowMs: Long,
        adverseTracker: AdverseSelectionTracker,
        fillStats: FillStats
    ) {
        val elapsedSec = ((nowMs - lastLogMs).coerceAtLeast(1L)) / 1000.0
        val totalFills = fillCounts.values.sum()
        val fillsPerMin = (totalFills - lastFillTotal) * (60.0 / elapsedSec)
        val positions =
            inventoryRepo.getInventory().filter { allowedSymbols.contains(it.symbol.value) }
        val maxAbsQty =
            positions.maxOfOrNull { kotlin.math.abs(it.quantity.value.toDouble()) } ?: 0.0
        val sumAbsQty = positions.sumOf { kotlin.math.abs(it.quantity.value.toDouble()) }
        val labels = adverseTracker.horizonsLabel()
        val advStr = positions.sortedBy { it.symbol.value }.joinToString(" ") { p ->
            val adv = adverseTracker.snapshotBps(p.symbol.value)
            val parts = labels.zip(adv).joinToString(",") { (label, value) ->
                val v = value?.let { "%.2f".format(it) } ?: "NA"
                "adv${label}=${v}"
            }
            "${p.symbol.value}[$parts]"
        }
        println(
            String.format(
                "health: fillsPerMin=%.2f positions=%d maxAbsQty=%.6f sumAbsQty=%.6f",
                fillsPerMin,
                positions.size,
                maxAbsQty,
                sumAbsQty
            )
        )
        println(
            String.format(
                "fills: total=%d maker=%d taker=%d notional=%.2f fees=%.4f",
                fillStats.totalCount(),
                fillStats.makerCount,
                fillStats.takerCount,
                fillStats.totalNotional,
                fillStats.totalFees
            )
        )
        println("adv: $advStr")
        val totalNet =
            positions.sumOf { it.realizedPnl.value.toDouble() + it.unrealizedPnl.value.toDouble() }
        val totalExposure =
            positions.sumOf { abs(it.quantity.value.toDouble() * it.avgPrice.value.toDouble()) }
        val avgAdv =
            positions.mapNotNull { adverseTracker.snapshotBps(it.symbol.value).firstOrNull() }
                .let { if (it.isEmpty()) null else it.average() }
        println(
            HealthSummary.render(
                strategy = "avellaneda",
                mode = "testnet",
                net = totalNet,
                fees = fillStats.totalFees,
                adverseBps = avgAdv,
                fills = fillStats.totalCount(),
                exposure = totalExposure
            )
        )
    }

    private suspend fun resolveSymbols(
        source: String,
        symbolsEnv: String?,
        topN: Int,
        universe: BinanceUniverse,
        futuresFilters: Map<String, com.example.network.futures.interfaces.FuturesSymbolFilters>?,
        minQuoteVolume: Double,
        minTrades: Long,
        quoteAssets: Set<String>
    ): List<String> {
        val futuresSymbols = futuresFilters?.keys ?: emptySet()
        if (!symbolsEnv.isNullOrBlank()) {
            return symbolsEnv.split(',')
                .map { it.trim().uppercase() }
                .filter { it.isNotBlank() }
                .filter { symbol -> futuresSymbols.isEmpty() || futuresSymbols.contains(symbol) }
        }
        println("Fetching universe...")
        val config = UniverseConfig(
            quoteAssets = quoteAssets,
            minQuoteVolume = minQuoteVolume,
            minTrades = minTrades,
            maxSymbols = topN
        )
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

    private fun parseQuoteStyle(raw: String?): QuoteStyle {
        return when (raw?.trim()?.uppercase()) {
            "IMPROVE" -> QuoteStyle.IMPROVE
            "WIDEN" -> QuoteStyle.WIDEN
            else -> QuoteStyle.JOIN
        }
    }

    private suspend fun buildReportRows(
        symbols: List<String>,
        inventoryRepo: CsvInventoryStateRepository,
        latestMid: Map<String, Double>,
        adverseTracker: AdverseSelectionTracker,
        fillCounts: Map<String, Int>,
        fillStats: FillStats,
        nowMs: Long
    ): List<AvellanedaReportRow> {
        val positions = inventoryRepo.getInventory().associateBy { it.symbol.value }
        val rows = symbols.sorted().map { symbol ->
            val pos = positions[symbol]
            val qty = pos?.quantity?.value?.toDouble() ?: 0.0
            val avg = pos?.avgPrice?.value?.toDouble() ?: 0.0
            val unrealized = pos?.unrealizedPnl?.value?.toDouble() ?: 0.0
            val realized = pos?.realizedPnl?.value?.toDouble() ?: 0.0
            val net = realized + unrealized
            val exposure = abs(qty * avg)
            val pnlPct = if (exposure > 0.0) net / exposure * 100.0 else 0.0
            AvellanedaReportRow(
                timestampMs = nowMs,
                symbol = symbol,
                mid = latestMid[symbol],
                qty = qty,
                avg = avg,
                unrealizedPnl = unrealized,
                realizedPnl = realized,
                netPnl = net,
                pnlPct = pnlPct,
                exposure = exposure,
                fills = fillCounts[symbol] ?: 0,
                makerFills = null,
                takerFills = null,
                totalFees = null,
                totalNotional = null,
                advBps = adverseTracker.snapshotBps(symbol)
            )
        }.toMutableList()

        val totals = positions.values
        val totalExposure =
            totals.sumOf { abs(it.quantity.value.toDouble() * it.avgPrice.value.toDouble()) }
        val totalRealized = totals.sumOf { it.realizedPnl.value.toDouble() }
        val totalUnrealized = totals.sumOf { it.unrealizedPnl.value.toDouble() }
        val totalNet = totalRealized + totalUnrealized
        val totalPct = if (totalExposure > 0.0) totalNet / totalExposure * 100.0 else 0.0
        rows.add(
            AvellanedaReportRow(
                timestampMs = nowMs,
                symbol = "TOTAL",
                mid = null,
                qty = 0.0,
                avg = 0.0,
                unrealizedPnl = totalUnrealized,
                realizedPnl = totalRealized,
                netPnl = totalNet,
                pnlPct = totalPct,
                exposure = totalExposure,
                fills = fillCounts.values.sum(),
                makerFills = fillStats.makerCount,
                takerFills = fillStats.takerCount,
                totalFees = fillStats.totalFees,
                totalNotional = fillStats.totalNotional,
                advBps = emptyList()
            )
        )
        return rows
    }
}
