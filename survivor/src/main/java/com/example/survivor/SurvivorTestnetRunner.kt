package com.example.survivor

import com.example.execution.domain.ExecutionGateway
import com.example.marketdata.model.MarketStateConfig
import com.example.marketdata.model.asSymbol
import com.example.marketdata.repository.FuturesMarketStateRepository
import com.example.network.di.networkModule
import com.example.network.futures.di.futuresModule
import com.example.network.futures.helper.FuturesUserStreamTelemetry
import com.example.network.futures.interfaces.BinanceFuturesTestNetApiService
import com.example.network.futures.interfaces.FuturesMarketDataService
import com.example.network.futures.interfaces.FuturesUserDataService
import com.example.network.futures.interfaces.FuturesWebSocketService
import com.example.platform.model.MarketState
import com.example.platform.report.ExperimentManifest
import com.example.platform.report.ExperimentManifestWriter
import com.example.platform.report.RunSummary
import com.example.platform.report.RunSummaryWriter
import com.example.platform.report.Telemetry
import com.example.execution.impl.ExecutionPolicy
import com.example.execution.impl.IntentAllocator
import com.example.execution.impl.RiskBudgetEnv
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import kotlin.time.Duration.Companion.milliseconds
import com.example.account.impl.di.accountImplModule
import com.example.execution.impl.di.executionImplModule
import java.io.File

object SurvivorTestnetRunner {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        Telemetry.configureFromEnv("survivor_testnet", defaultEnabled = true)
        val symbol = System.getenv("SYMBOL") ?: "BTCUSDT"
        val tickMs = System.getenv("TICK_MS")?.toLongOrNull() ?: 1_000L
        val depthLevels = System.getenv("DEPTH_LEVELS")?.toIntOrNull() ?: 5
        val depthSpeedMs = System.getenv("DEPTH_SPEED_MS")?.toLongOrNull() ?: 250L
        val snapshotDepth = System.getenv("SNAPSHOT_DEPTH")?.toIntOrNull() ?: 50
        val pollMs = System.getenv("FUNDING_POLL_MS")?.toLongOrNull() ?: 5_000L
        val positionPollMs = System.getenv("POSITION_POLL_MS")?.toLongOrNull() ?: 5_000L
        val logEveryMs = System.getenv("LOG_KPI_EVERY_MS")?.toLongOrNull() ?: 60_000L
        val recordEnabled = System.getenv("RECORD_SURVIVOR_CSV")?.toBooleanStrictOrNull() ?: false
        val recordEveryMs = System.getenv("RECORD_EVERY_MS")?.toLongOrNull() ?: 1_000L
        val recordPath = System.getenv("SURVIVOR_RECORD_PATH") ?: defaultRecordPath()
        val recordTimestamped = System.getenv("RECORD_TIMESTAMPED")?.toBooleanStrictOrNull() ?: false
        val recordTruncate = System.getenv("RECORD_TRUNCATE")?.toBooleanStrictOrNull() ?: true
        val leverage = (System.getenv("LEVERAGE")?.toIntOrNull() ?: 1).coerceIn(1, 2)

        println("Survivor testnet starting...")
        println("Symbol       : $symbol")
        println("TickMs       : $tickMs")
        println("DepthLevels  : $depthLevels")
        println("DepthSpeedMs : $depthSpeedMs")
        println("SnapDepth    : $snapshotDepth")
        println("PollMs       : $pollMs")
        println("PosPollMs    : $positionPollMs")
        println("Leverage     : $leverage")
        var recordOutputPath: String? = null
        if (recordEnabled) {
            recordOutputPath = applyTimestamp(recordPath, recordTimestamped)
            println("RecordCsv    : $recordOutputPath everyMs=$recordEveryMs truncate=$recordTruncate")
        }

        val koinApp = startKoin {
            modules(networkModule, futuresModule, accountImplModule, executionImplModule)
        }
        val koin = koinApp.koin

        try {
            val api = koin.get<BinanceFuturesTestNetApiService>(named("futuresTestnetApi"))
            try {
                api.setLeverage(symbol, leverage)
            } catch (e: Exception) {
                println("Leverage set failed for $symbol: ${e.message}")
            }

            val repo = koin.get<FuturesMarketStateRepository>()
            val marketData = koin.get<FuturesMarketDataService>()
            val gateway = koin.get<ExecutionGateway>(named("futuresExecution"))

            val config = MarketStateConfig(
                tick = tickMs.milliseconds,
                depthLevels = depthLevels,
                depthSpeed = depthSpeedMs.milliseconds,
                snapshotDepthLimit = snapshotDepth
            )

            val survivorConfig = configFromEnv(symbol)
            val kpi = SurvivorKpiTracker(survivorConfig)
            val strategy = SurvivorIntentStrategy(survivorConfig)
            val allocator = IntentAllocator(
                riskBudget = RiskBudgetEnv.fromEnv(
                    defaultTotal = 1e12,
                    defaultShares = mapOf("survivor" to 1.0)
                )
            )
            val policy = ExecutionPolicy(gateway)
            val engine = SurvivorPortfolioEngine(
                gateway,
                allocator,
                policy,
                strategy,
                heartbeatMs = survivorConfig.rebalanceIntervalMs,
                staleFeedMs = survivorConfig.staleFeedMs
            )
            val recorder = if (recordEnabled) {
                val resolved = recordOutputPath ?: applyTimestamp(recordPath, recordTimestamped)
                val outFile = File(resolved)
                if (recordTruncate && outFile.exists()) {
                    outFile.delete()
                }
                SurvivorCsvRecorder(outFile, recordEveryMs)
            } else {
                null
            }
            val telemetryPath = Telemetry.resolveReportPathFromEnv("survivor_testnet", defaultEnabled = true)
            val manifestWriter = ExperimentManifestWriter.fromEnv()
            manifestWriter?.write(
                ExperimentManifest(
                    timestampMs = System.currentTimeMillis(),
                    strategy = "survivor",
                    mode = "testnet",
                    symbols = listOf(symbol),
                    params = mapOf(
                        "SYMBOL" to symbol,
                        "ENTRY_FUNDING" to (System.getenv("ENTRY_FUNDING") ?: ""),
                        "EXIT_FUNDING" to (System.getenv("EXIT_FUNDING") ?: ""),
                        "BASIS_STOP_PCT" to (System.getenv("BASIS_STOP_PCT") ?: ""),
                        "MAX_VOL" to (System.getenv("MAX_VOL") ?: ""),
                        "MAX_SPREAD_PCT" to (System.getenv("MAX_SPREAD_PCT") ?: ""),
                        "LEVERAGE" to leverage.toString()
                    ).filterValues { it.isNotBlank() },
                    reportPath = telemetryPath,
                    runId = System.getenv("RUN_ID"),
                    notes = System.getenv("RUN_NOTES")
                )
            )

            val dataMutex = Mutex()
            var latestPremium: PremiumSnapshot? = null

            launch {
                while (true) {
                    try {
                        val premium = marketData.getPremiumIndex(symbol)
                        val oi = marketData.getOpenInterest(symbol)
                        val snap = PremiumSnapshot(
                            fundingRate = premium.lastFundingRate.toDoubleOrNull() ?: 0.0,
                            nextFundingTimeMs = premium.nextFundingTime,
                            markPrice = premium.markPrice.toDoubleOrNull() ?: 0.0,
                            indexPrice = premium.indexPrice.toDoubleOrNull() ?: 0.0,
                            openInterest = oi.openInterest.toDoubleOrNull() ?: 0.0
                        )
                        dataMutex.withLock { latestPremium = snap }
                    } catch (e: Exception) {
            println("Premium/OI poll failed: ${e.message}")
            }
            delay(pollMs)
            }
        }
            var lastKpiMs = 0L
            var heartbeatJob: kotlinx.coroutines.Job? = null
            var lastPosPollMs = 0L
            val userData = koin.get<FuturesUserDataService>()
            val userWs = koin.get<FuturesWebSocketService>()
            FuturesUserStreamTelemetry.start(this, userData, userWs)
            val flow: Flow<MarketState> = repo.streamMarketState(listOf(symbol.asSymbol()), config)
            try {
                heartbeatJob = engine.startHeartbeat(this)
                flow.collect { state ->
                    val premium = dataMutex.withLock { latestPremium }
                    if (premium == null) return@collect
                    val snapshot = buildSnapshot(symbol, state, premium)
                    engine.onSnapshot(snapshot)
                    recorder?.record(snapshot)

                    val now = snapshot.timestampMs
                    if (now - lastPosPollMs >= positionPollMs) {
                        val pos = gateway.getPositions().firstOrNull { it.symbol.value == symbol }
                        if (pos != null) {
                            kpi.onPositionUpdate(symbol, pos.quantity.value.toDouble(), pos.averagePrice.value.toDouble())
                        }
                        lastPosPollMs = now
                    }
                    if (lastKpiMs == 0L) lastKpiMs = now
                    if (now - lastKpiMs >= logEveryMs) {
                        SurvivorReport.print(kpi.summary(), "SURVIVOR TESTNET KPI")
                        lastKpiMs = now
                    }
                }
            } finally {
                heartbeatJob?.cancel()
                val summary = kpi.summary()
                SurvivorReport.print(summary, "SURVIVOR TESTNET KPI")
                writeSurvivorTestnetSummary(
                    config = survivorConfig,
                    summary = summary,
                    telemetryPath = telemetryPath,
                    manifestPath = manifestWriter?.path(),
                    recordPath = recordOutputPath,
                    mode = "testnet"
                )
            }
        } finally {
            stopKoin()
        }
    }

    private fun writeSurvivorTestnetSummary(
        config: SurvivorConfig,
        summary: SurvivorKpiSummary,
        telemetryPath: String?,
        manifestPath: String?,
        recordPath: String?,
        mode: String
    ) {
        val configs = mapOf(
            "symbol" to config.symbol,
            "order_qty" to config.orderQty.toString(),
            "entry_funding_threshold" to config.entryFundingThreshold.toString(),
            "exit_funding_threshold" to config.exitFundingThreshold.toString(),
            "basis_stop_pct" to config.basisStopAbsPct.toString()
        ).filterValues { it.isNotBlank() }
        RunSummaryWriter.writeSummary(
            root = findProjectRoot(),
            summary = RunSummary(
                strategy = "survivor",
                mode = mode,
                timestampMs = System.currentTimeMillis(),
                configs = configs,
                metrics = mapOf(
                    "net_carry" to summary.netCarry,
                    "realized_funding" to summary.realizedFunding,
                    "realized_fees" to summary.realizedFees,
                    "borrow_costs" to summary.borrowCosts,
                    "expected_carry" to summary.expectedCarry,
                    "worst_basis_abs_pct" to summary.worstBasisAbsPct
                ),
                health = mapOf(
                    "cancel_rate" to summary.cancelRate,
                    "stale_cancel_rate" to summary.staleCancelRate
                ),
                notes = mapOfNotNulls(
                    "telemetry_path" to telemetryPath,
                    "manifest_path" to manifestPath,
                    "record_path" to recordPath,
                    "run_id" to System.getenv("RUN_ID"),
                    "run_notes" to System.getenv("RUN_NOTES")
                )
            )
        )
    }

    private fun mapOfNotNulls(vararg pairs: Pair<String, String?>): Map<String, String> {
        return pairs.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
    }

    private fun buildSnapshot(
        symbol: String,
        state: MarketState,
        premium: PremiumSnapshot
    ): SurvivorSnapshot {
        val mid = state.midPrice ?: state.microPrice
        val spread = state.spread
        val spreadPct = if (spread != null && mid != null && mid > 0.0) {
            spread / mid
        } else {
            0.0
        }
        val vol = state.vol1s ?: state.vol5s ?: 0.0
        val ts = state.eventTimeMs ?: state.timestampMs
        return SurvivorSnapshot(
            symbol = symbol,
            timestampMs = ts,
            fundingRate = premium.fundingRate,
            nextFundingTimeMs = premium.nextFundingTimeMs,
            markPrice = premium.markPrice,
            indexPrice = premium.indexPrice,
            spreadPct = spreadPct,
            volatility = vol,
            openInterest = premium.openInterest
        )
    }

    private fun configFromEnv(symbol: String): SurvivorConfig {
        val base = SurvivorConfig(symbol = symbol)
        return base.copy(
            orderQty = System.getenv("ORDER_QTY")?.toDoubleOrNull() ?: base.orderQty,
            entryFundingThreshold = System.getenv("ENTRY_FUNDING")?.toDoubleOrNull()
                ?: base.entryFundingThreshold,
            exitFundingThreshold = System.getenv("EXIT_FUNDING")?.toDoubleOrNull()
                ?: base.exitFundingThreshold,
            entryBasisAbsPctMax = System.getenv("ENTRY_BASIS_PCT_MAX")?.toDoubleOrNull()
                ?: base.entryBasisAbsPctMax,
            maxTimeToFundingForTakerMs = System.getenv("MAX_TIME_TO_FUNDING_TAKER_MS")?.toLongOrNull()
                ?: base.maxTimeToFundingForTakerMs,
            basisStopAbsPct = System.getenv("BASIS_STOP_PCT")?.toDoubleOrNull()
                ?: base.basisStopAbsPct,
            maxVolatility = System.getenv("MAX_VOL")?.toDoubleOrNull() ?: base.maxVolatility,
            maxSpreadPct = System.getenv("MAX_SPREAD_PCT")?.toDoubleOrNull() ?: base.maxSpreadPct,
            maxOiJumpPct = System.getenv("MAX_OI_JUMP_PCT")?.toDoubleOrNull() ?: base.maxOiJumpPct,
            oiWindowMs = System.getenv("OI_WINDOW_MS")?.toLongOrNull() ?: base.oiWindowMs,
            maxHoldMs = System.getenv("MAX_HOLD_MS")?.toLongOrNull() ?: base.maxHoldMs,
            orderTtlMs = System.getenv("ORDER_TTL_MS")?.toLongOrNull() ?: base.orderTtlMs,
            rebalanceIntervalMs = System.getenv("REBALANCE_INTERVAL_MS")?.toLongOrNull()
                ?: base.rebalanceIntervalMs,
            staleFeedMs = System.getenv("STALE_FEED_MS")?.toLongOrNull() ?: base.staleFeedMs,
            makerFeePct = System.getenv("MAKER_FEE_PCT")?.toDoubleOrNull() ?: base.makerFeePct,
            takerFeePct = System.getenv("TAKER_FEE_PCT")?.toDoubleOrNull() ?: base.takerFeePct,
            borrowFeePctPerDay = System.getenv("BORROW_FEE_PCT_DAY")?.toDoubleOrNull()
                ?: base.borrowFeePctPerDay,
            allowHedge = System.getenv("ALLOW_HEDGE")?.toBooleanStrictOrNull() ?: base.allowHedge,
            logSignals = System.getenv("LOG_SIGNALS")?.toBooleanStrictOrNull() ?: base.logSignals
        )
    }

    private data class PremiumSnapshot(
        val fundingRate: Double,
        val nextFundingTimeMs: Long,
        val markPrice: Double,
        val indexPrice: Double,
        val openInterest: Double
    )

    private fun defaultRecordPath(): String {
        val root = findProjectRoot()
        return File(root, "survivor.csv").absolutePath
    }

    private fun findProjectRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        while (true) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            val parent = dir.parentFile ?: return dir
            dir = parent
        }
    }

    private fun applyTimestamp(path: String, timestamped: Boolean): String {
        if (!timestamped) return path
        val file = File(path)
        val name = file.name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        val ts = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
            .withZone(java.time.ZoneOffset.UTC)
            .format(java.time.Instant.now())
        val stamped = "${base}_$ts$ext"
        val parent = file.parentFile
        return if (parent == null) stamped else File(parent, stamped).absolutePath
    }
}
