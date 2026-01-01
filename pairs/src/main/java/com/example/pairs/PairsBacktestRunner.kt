package com.example.pairs

import com.example.execution.impl.ConservativeFillSimulator
import com.example.execution.impl.SimAccountStateRepository
import com.example.execution.impl.SimExecutionGateway
import com.example.execution.impl.ExecutionPolicy
import com.example.execution.impl.IntentAllocator
import com.example.execution.impl.PortfolioEngine
import com.example.execution.impl.RiskBudgetEnv
import com.example.marketdata.impl.replay.MarketStateReplayer
import com.example.platform.report.ExperimentManifest
import com.example.platform.report.ExperimentManifestWriter
import com.example.platform.report.RunSummary
import com.example.platform.report.RunSummaryWriter
import com.example.platform.report.Telemetry
import kotlinx.coroutines.runBlocking
import java.io.File

object PairsBacktestRunner {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        Telemetry.configureFromEnv("pairs_backtest", defaultEnabled = true)
        val inputPath = args.getOrNull(0)
            ?: System.getenv("MARKETSTATE_CSV")
            ?: defaultMarketStatePath()
        val symbolA = System.getenv("SYMBOL_A") ?: args.getOrNull(1) ?: "BTCUSDT"
        val symbolB = System.getenv("SYMBOL_B") ?: args.getOrNull(2) ?: "ETHUSDT"
        val speedup = args.getOrNull(3)?.toDoubleOrNull()
            ?: System.getenv("REPLAY_SPEEDUP")?.toDoubleOrNull()
            ?: 1.0
        val kpiEveryMs = System.getenv("LOG_KPI_EVERY_MS")?.toLongOrNull() ?: 60_000L

        val inputFile = File(inputPath)
        println("Pairs backtest input: ${inputFile.absolutePath}")
        println("Exists             : ${inputFile.exists()} sizeBytes=${if (inputFile.exists()) inputFile.length() else 0L}")
        println("Symbols            : $symbolA/$symbolB")

        val replayer = MarketStateReplayer(File(inputPath), speedup = speedup)
        val accountRepo = SimAccountStateRepository()
        val orderLatencyMs = System.getenv("SIM_ORDER_LATENCY_MS")?.toLongOrNull() ?: 100L
        val queueBuffer = System.getenv("SIM_QUEUE_BUFFER")?.toDoubleOrNull() ?: 1.0
        val queueLevels = System.getenv("SIM_MAX_QUEUE_LEVELS")?.toIntOrNull() ?: 5
        val makerFeePct = System.getenv("MAKER_FEE_PCT")?.toDoubleOrNull() ?: 0.0002
        val takerFeePct = System.getenv("TAKER_FEE_PCT")?.toDoubleOrNull() ?: 0.0004
        val fillSimulator = ConservativeFillSimulator(
            queueBufferMultiplier = queueBuffer,
            maxDepthLevels = queueLevels
        )
        val config = configFromEnv(symbolA, symbolB)
        val kpi = PairsKpiTracker(config)
        val gateway = SimExecutionGateway(
            accountRepo,
            inventoryStateRepository = null,
            fillSimulator = fillSimulator,
            orderLatencyMs = orderLatencyMs,
            makerFeePct = makerFeePct,
            takerFeePct = takerFeePct
        )
        val strategy = PairsIntentStrategy(config = config)
        val allocator = IntentAllocator(
            riskBudget = RiskBudgetEnv.fromEnv(
                defaultTotal = 1e12,
                defaultShares = mapOf("pairs" to 1.0)
            )
        )
        val policy = ExecutionPolicy(gateway)
        val engine = PortfolioEngine(gateway, allocator, policy, listOf(strategy))
        val telemetryPath = Telemetry.resolveReportPathFromEnv("pairs_backtest", defaultEnabled = true)
        val manifestWriter = ExperimentManifestWriter.fromEnv()
        manifestWriter?.write(
            ExperimentManifest(
                timestampMs = System.currentTimeMillis(),
                strategy = "pairs",
                mode = "backtest",
                symbols = listOf(symbolA, symbolB),
                params = mapOf(
                    "MARKETSTATE_CSV" to inputPath,
                    "REPLAY_SPEEDUP" to speedup.toString(),
                    "ENTRY_Z" to (System.getenv("ENTRY_Z") ?: ""),
                    "EXIT_Z" to (System.getenv("EXIT_Z") ?: "")
                ).filterValues { it.isNotBlank() },
                reportPath = telemetryPath,
                runId = System.getenv("RUN_ID"),
                notes = System.getenv("RUN_NOTES")
            )
        )

        var lastKpiMs = 0L
        replayer.stream().collect { state ->
            if (state.symbol != symbolA && state.symbol != symbolB) return@collect
            gateway.onMarketState(state)
            engine.onMarketState(state)
            val now = state.eventTimeMs ?: state.timestampMs
            if (lastKpiMs == 0L) lastKpiMs = now
            if (now - lastKpiMs >= kpiEveryMs) {
                PairsReport.print(kpi.summary(), "PAIRS BACKTEST KPI")
                lastKpiMs = now
            }
        }
        val finalSummary = kpi.summary()
        PairsReport.print(finalSummary, "PAIRS BACKTEST KPI")
        writePairsSummary(
            config = config,
            summary = finalSummary,
            telemetryPath = telemetryPath,
            manifestPath = manifestWriter?.path(),
            mode = "backtest"
        )
    }

    private fun defaultMarketStatePath(): String {
        val root = findProjectRoot()
        return File(root, "marketstate.csv").absolutePath
    }

    private fun findProjectRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        while (true) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            val parent = dir.parentFile ?: return dir
            dir = parent
        }
    }

    private fun configFromEnv(symbolA: String, symbolB: String): PairsConfig {
        val base = PairsConfig(symbolA = symbolA, symbolB = symbolB)
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
            orderTtlMs = System.getenv("ORDER_TTL_MS")?.toLongOrNull() ?: base.orderTtlMs,
            makerFeePct = System.getenv("MAKER_FEE_PCT")?.toDoubleOrNull() ?: base.makerFeePct,
            takerFeePct = System.getenv("TAKER_FEE_PCT")?.toDoubleOrNull() ?: base.takerFeePct,
            tailZ = System.getenv("TAIL_Z")?.toDoubleOrNull() ?: base.tailZ,
            logSignals = System.getenv("LOG_SIGNALS")?.toBooleanStrictOrNull() ?: base.logSignals
        )
    }

    private fun writePairsSummary(
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
            "exit_z" to config.exitZ.toString()
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
}
