package com.example.survivor

import com.example.marketdata.model.MarketStateConfig
import com.example.marketdata.model.asSymbol
import com.example.marketdata.repository.FuturesMarketStateRepository
import com.example.network.di.networkModule
import com.example.network.futures.di.futuresModule
import com.example.network.futures.interfaces.FuturesMarketDataService
import com.example.platform.model.MarketState
import com.example.platform.report.ExperimentManifest
import com.example.platform.report.ExperimentManifestWriter
import java.io.File
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

object SurvivorBacktestRunner {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val inputPath = args.getOrNull(0)
            ?: System.getenv("SURVIVOR_CSV")
            ?: defaultPath()
        val fastMode = System.getenv("FAST_MODE")?.toBooleanStrictOrNull() ?: false
        val speedup = args.getOrNull(1)?.toDoubleOrNull()
            ?: System.getenv("REPLAY_SPEEDUP")?.toDoubleOrNull()
            ?: if (fastMode) 50.0 else 1.0
        val logEveryMs = System.getenv("LOG_KPI_EVERY_MS")?.toLongOrNull()
            ?: if (fastMode) 300_000L else 60_000L

        val file = File(inputPath)
        println("Survivor backtest input: ${file.absolutePath}")
        println("Exists                : ${file.exists()} sizeBytes=${if (file.exists()) file.length() else 0L}")

        val recordBefore = System.getenv("SURVIVOR_RECORD_BEFORE")?.toBooleanStrictOrNull() ?: true
        if (recordBefore) {
            recordSnapshots(file)
        }

        val config = configFromEnv()
        val kpi = SurvivorKpiTracker(config)
        val gates = SurvivorGateStats()
        val gateway = SurvivorPaperGateway { fill -> kpi.onFill(fill) }
        val strategy = SurvivorStrategy(gateway, config, kpi, gates)
        val replayer = SurvivorCsvReplayer(file, speedup = speedup)
        val manifestWriter = ExperimentManifestWriter.fromEnv()
        manifestWriter?.write(
            ExperimentManifest(
                timestampMs = System.currentTimeMillis(),
                strategy = "survivor",
                mode = "backtest",
                symbols = listOf(config.symbol),
                params = mapOf(
                    "SURVIVOR_CSV" to inputPath,
                    "REPLAY_SPEEDUP" to speedup.toString(),
                    "ENTRY_FUNDING" to (System.getenv("ENTRY_FUNDING") ?: ""),
                    "EXIT_FUNDING" to (System.getenv("EXIT_FUNDING") ?: ""),
                    "BASIS_STOP_PCT" to (System.getenv("BASIS_STOP_PCT") ?: "")
                ).filterValues { it.isNotBlank() },
                reportPath = null,
                runId = System.getenv("RUN_ID"),
                notes = System.getenv("RUN_NOTES")
            )
        )

        var lastKpiMs = 0L
        replayer.stream().collect { snap ->
            strategy.onSnapshot(snap)
            val now = snap.timestampMs
            if (lastKpiMs == 0L) lastKpiMs = now
            if (now - lastKpiMs >= logEveryMs) {
                SurvivorReport.print(kpi.summary(), "SURVIVOR BACKTEST KPI")
                println(gates.report())
                lastKpiMs = now
            }
        }
        SurvivorReport.print(kpi.summary(), "SURVIVOR BACKTEST KPI")
        println(gates.report())
    }

    private fun defaultPath(): String {
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

    private fun configFromEnv(): SurvivorConfig {
        val base = SurvivorConfig(symbol = System.getenv("SYMBOL") ?: "BTCUSDT")
        return base.copy(
            orderQty = System.getenv("ORDER_QTY")?.toDoubleOrNull() ?: base.orderQty,
            entryFundingThreshold = System.getenv("ENTRY_FUNDING")?.toDoubleOrNull()
                ?: base.entryFundingThreshold,
            exitFundingThreshold = System.getenv("EXIT_FUNDING")?.toDoubleOrNull()
                ?: base.exitFundingThreshold,
            basisStopAbsPct = System.getenv("BASIS_STOP_PCT")?.toDoubleOrNull()
                ?: base.basisStopAbsPct,
            maxVolatility = System.getenv("MAX_VOL")?.toDoubleOrNull() ?: base.maxVolatility,
            maxSpreadPct = System.getenv("MAX_SPREAD_PCT")?.toDoubleOrNull() ?: base.maxSpreadPct,
            maxOiJumpPct = System.getenv("MAX_OI_JUMP_PCT")?.toDoubleOrNull() ?: base.maxOiJumpPct,
            oiWindowMs = System.getenv("OI_WINDOW_MS")?.toLongOrNull() ?: base.oiWindowMs,
            maxHoldMs = System.getenv("MAX_HOLD_MS")?.toLongOrNull() ?: base.maxHoldMs,
            orderTtlMs = System.getenv("ORDER_TTL_MS")?.toLongOrNull() ?: base.orderTtlMs,
            makerFeePct = System.getenv("MAKER_FEE_PCT")?.toDoubleOrNull() ?: base.makerFeePct,
            takerFeePct = System.getenv("TAKER_FEE_PCT")?.toDoubleOrNull() ?: base.takerFeePct,
            borrowFeePctPerDay = System.getenv("BORROW_FEE_PCT_DAY")?.toDoubleOrNull()
                ?: base.borrowFeePctPerDay,
            allowHedge = System.getenv("ALLOW_HEDGE")?.toBooleanStrictOrNull() ?: base.allowHedge,
            logSignals = System.getenv("LOG_SIGNALS")?.toBooleanStrictOrNull() ?: base.logSignals
        )
    }

    private suspend fun recordSnapshots(outputFile: File) {
        val symbol = System.getenv("SYMBOL") ?: "BTCUSDT"
        val recordMs = System.getenv("SURVIVOR_RECORD_MS")?.toLongOrNull()
            ?: ((System.getenv("SURVIVOR_RECORD_SEC")?.toLongOrNull() ?: 600L) * 1_000L)
        val pollMs = System.getenv("SURVIVOR_RECORD_POLL_MS")?.toLongOrNull() ?: 5_000L
        val tickMs = System.getenv("TICK_MS")?.toLongOrNull() ?: 1_000L
        val depthLevels = System.getenv("DEPTH_LEVELS")?.toIntOrNull() ?: 5
        val depthSpeedMs = System.getenv("DEPTH_SPEED_MS")?.toLongOrNull() ?: 250L
        val snapshotDepth = System.getenv("SNAPSHOT_DEPTH")?.toIntOrNull() ?: 50

        println("Recording survivor snapshots...")
        println("Symbol       : $symbol")
        println("DurationMs   : $recordMs")
        println("PollMs       : $pollMs")

        val koinApp = startKoin {
            modules(networkModule, futuresModule)
        }
        val koin = koinApp.koin

        try {
            val repo = koin.get<FuturesMarketStateRepository>()
            val marketData = koin.get<FuturesMarketDataService>()
            val config = MarketStateConfig(
                tick = tickMs.milliseconds,
                depthLevels = depthLevels,
                depthSpeed = depthSpeedMs.milliseconds,
                snapshotDepthLimit = snapshotDepth
            )

            outputFile.parentFile?.mkdirs()
            outputFile.writeText(
                "symbol,timestampMs,fundingRate,nextFundingTimeMs,markPrice,indexPrice,spreadPct,volatility,openInterest\n"
            )

            val dataMutex = Mutex()
            var latestPremium: PremiumSnapshot? = null
            kotlinx.coroutines.coroutineScope {
                val pollJob = launch {
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

                val symbolList = listOf(symbol.asSymbol())
                val flow = repo.streamMarketState(symbolList, config)
                withTimeoutOrNull(recordMs) {
                    flow.collect { state ->
                        val premium = dataMutex.withLock { latestPremium } ?: return@collect
                        val snapshot = buildSnapshot(symbol, state, premium)
                        outputFile.appendText(serializeSnapshot(snapshot))
                    }
                }
                pollJob.cancel()
            }
            println("Recording complete. File: ${outputFile.absolutePath}")
        } finally {
            stopKoin()
        }
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

    private fun serializeSnapshot(snapshot: SurvivorSnapshot): String {
        return String.format(
            Locale.US,
            "%s,%d,%.10f,%d,%.8f,%.8f,%.8f,%.8f,%.8f\n",
            snapshot.symbol,
            snapshot.timestampMs,
            snapshot.fundingRate,
            snapshot.nextFundingTimeMs,
            snapshot.markPrice,
            snapshot.indexPrice,
            snapshot.spreadPct,
            snapshot.volatility,
            snapshot.openInterest
        )
    }

    private data class PremiumSnapshot(
        val fundingRate: Double,
        val nextFundingTimeMs: Long,
        val markPrice: Double,
        val indexPrice: Double,
        val openInterest: Double
    )

}
