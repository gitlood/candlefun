package com.example.vacuum

import com.example.execution.impl.ConservativeFillSimulator
import com.example.execution.impl.SimAccountStateRepository
import com.example.execution.impl.SimExecutionGateway
import com.example.execution.domain.RiskBudget
import com.example.execution.impl.ExecutionPolicy
import com.example.execution.impl.IntentAllocator
import com.example.execution.impl.PortfolioEngine
import com.example.marketdata.impl.replay.MarketStateReplayer
import com.example.platform.report.ExperimentManifest
import com.example.platform.report.ExperimentManifestWriter
import com.example.platform.report.GistUploader
import com.example.platform.report.RunSummary
import com.example.platform.report.RunSummaryWriter
import com.example.platform.report.Telemetry
import kotlinx.coroutines.runBlocking
import java.io.File

object VacuumBacktestRunner {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        Telemetry.configureFromEnv("vacuum_backtest", defaultEnabled = true)
        val inputPath = args.getOrNull(0)
            ?: System.getenv("MARKETSTATE_CSV")
            ?: defaultMarketStatePath()
        val symbolArg = args.getOrNull(1)
        val symbolsEnv = System.getenv("SYMBOLS")
        val fastMode = System.getenv("FAST_MODE")?.toBooleanStrictOrNull() ?: false
        val speedup = args.getOrNull(2)?.toDoubleOrNull()
            ?: System.getenv("REPLAY_SPEEDUP")?.toDoubleOrNull()
            ?: if (fastMode) 50.0 else 1.0
        val logEvery = System.getenv("LOG_EVERY_TICKS")?.toLongOrNull()
            ?: if (fastMode) 10_000L else 1_000L
        val kpiEveryMs = System.getenv("LOG_KPI_EVERY_MS")?.toLongOrNull()
            ?: if (fastMode) 300_000L else 60_000L

        val inputFile = File(inputPath)
        println("Vacuum backtest input: ${inputFile.absolutePath}")
        println("Exists               : ${inputFile.exists()} sizeBytes=${if (inputFile.exists()) inputFile.length() else 0L}")

        val replayer = MarketStateReplayer(File(inputPath), speedup = speedup)
        val accountRepo = SimAccountStateRepository()
        val orderLatencyMs = System.getenv("SIM_ORDER_LATENCY_MS")?.toLongOrNull() ?: 0L
        val queueBuffer = System.getenv("SIM_QUEUE_BUFFER")?.toDoubleOrNull() ?: 1.0
        val queueLevels = System.getenv("SIM_MAX_QUEUE_LEVELS")?.toIntOrNull() ?: 5
        val makerFeePct = System.getenv("MAKER_FEE_PCT")?.toDoubleOrNull() ?: 0.0002
        val takerFeePct = System.getenv("TAKER_FEE_PCT")?.toDoubleOrNull() ?: 0.0004
        val fillSimulator = ConservativeFillSimulator(
            queueBufferMultiplier = queueBuffer,
            maxDepthLevels = queueLevels
        )
        val config = configFromEnv(symbolArg, symbolsEnv, fastMode)
        val kpi = VacuumKpiTracker(config)
        val gateway = SimExecutionGateway(
            accountRepo,
            inventoryStateRepository = null,
            fillSimulator = fillSimulator,
            orderLatencyMs = orderLatencyMs,
            makerFeePct = makerFeePct,
            takerFeePct = takerFeePct,
            fillListener = { fill -> kpi.onFill(fill) }
        )
        val strategy = VacuumIntentStrategy(config = config, kpi = kpi)
        val allocator = IntentAllocator(riskBudget = RiskBudget(total = 1e12))
        val policy = ExecutionPolicy(gateway)
        val engine = PortfolioEngine(gateway, allocator, policy, listOf(strategy))
        val telemetryPath = Telemetry.resolveReportPathFromEnv("vacuum_backtest", defaultEnabled = true)
        val manifestWriter = ExperimentManifestWriter.fromEnv()
        manifestWriter?.write(
            ExperimentManifest(
                timestampMs = System.currentTimeMillis(),
                strategy = "vacuum",
                mode = "backtest",
                symbols = listOf(config.symbol),
                params = mapOf(
                    "MARKETSTATE_CSV" to inputPath,
                    "REPLAY_SPEEDUP" to speedup.toString(),
                    "DEPTH_DROP_PCT" to (System.getenv("DEPTH_DROP_PCT") ?: ""),
                    "SPREAD_WIDEN_PCT" to (System.getenv("SPREAD_WIDEN_PCT") ?: "")
                ).filterValues { it.isNotBlank() },
                reportPath = telemetryPath,
                runId = System.getenv("RUN_ID"),
                notes = System.getenv("RUN_NOTES")
            )
        )
        GistUploader.installUploadOnShutdown(
            label = "vacuum_backtest",
            files = listOfNotNull(
                telemetryPath?.let { File(it) },
                manifestWriter?.path()?.let { File(it) }
            )
        )

        var ticks = 0L
        var lastKpiMs = 0L
        replayer.stream().collect { state ->
            if (!matchesSymbol(state.symbol, config.symbol)) return@collect
            gateway.onMarketState(state)
            engine.onMarketState(state)
            kpi.onMarketState(state.symbol, state.midPrice ?: state.microPrice, state.timestampMs)
            ticks++
            val now = state.eventTimeMs ?: state.timestampMs
            if (ticks % logEvery == 0L) {
                println("ticks=$ticks last=${state.symbol} t=${state.timestampMs}")
            }
            if (lastKpiMs == 0L) lastKpiMs = now
            if (now - lastKpiMs >= kpiEveryMs) {
                VacuumReport.print(kpi.summary(), "VACUUM BACKTEST KPI")
                lastKpiMs = now
            }
        }
        val finalSummary = kpi.summary()
        VacuumReport.print(finalSummary, "VACUUM BACKTEST KPI")
        writeVacuumSummary(
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

    private fun matchesSymbol(stateSymbol: String, target: String): Boolean {
        return target.isBlank() || stateSymbol.equals(target, ignoreCase = true)
    }

    private fun configFromEnv(
        symbolArg: String?,
        symbolsEnv: String?,
        fastMode: Boolean
    ): VacuumConfig {
        val symbol = symbolArg?.ifBlank { null } ?: symbolsEnv?.ifBlank { null }
            ?: if (fastMode) "BTCUSDT" else "BTCUSDT"
        val base = VacuumConfig(symbol = symbol)
        return base.copy(
            depthLevels = System.getenv("DEPTH_LEVELS")?.toIntOrNull() ?: base.depthLevels,
            depthWindowMs = System.getenv("DEPTH_WINDOW_MS")?.toLongOrNull() ?: base.depthWindowMs,
            depthDropPct = System.getenv("DEPTH_DROP_PCT")?.toDoubleOrNull() ?: base.depthDropPct,
            depthRefillPct = System.getenv("DEPTH_REFILL_PCT")?.toDoubleOrNull() ?: base.depthRefillPct,
            spreadWindowMs = System.getenv("SPREAD_WINDOW_MS")?.toLongOrNull() ?: base.spreadWindowMs,
            spreadWidenPct = System.getenv("SPREAD_WIDEN_PCT")?.toDoubleOrNull() ?: base.spreadWidenPct,
            maxSpreadPct = System.getenv("MAX_SPREAD_PCT")?.toDoubleOrNull() ?: base.maxSpreadPct,
            minTradeCount1s = System.getenv("MIN_TRADE_COUNT_1S")?.toIntOrNull() ?: base.minTradeCount1s,
            minTradeImbalance1s = System.getenv("MIN_TRADE_IMB_1S")?.toDoubleOrNull()
                ?: base.minTradeImbalance1s,
            orderQty = System.getenv("ORDER_QTY")?.toDoubleOrNull() ?: base.orderQty,
            priceTick = System.getenv("PRICE_TICK")?.toDoubleOrNull() ?: base.priceTick,
            qtyStep = System.getenv("QTY_STEP")?.toDoubleOrNull() ?: base.qtyStep,
            entryCooldownMs = System.getenv("ENTRY_COOLDOWN_MS")?.toLongOrNull() ?: base.entryCooldownMs,
            orderTtlMs = System.getenv("ORDER_TTL_MS")?.toLongOrNull() ?: base.orderTtlMs,
            maxHoldMs = System.getenv("MAX_HOLD_MS")?.toLongOrNull() ?: base.maxHoldMs,
            trailingStopBps = System.getenv("TRAILING_STOP_BPS")?.toDoubleOrNull()
                ?: base.trailingStopBps,
            slippagePauseBps = System.getenv("SLIPPAGE_PAUSE_BPS")?.toDoubleOrNull()
                ?: base.slippagePauseBps,
            tailLossBps = System.getenv("TAIL_LOSS_BPS")?.toDoubleOrNull() ?: base.tailLossBps,
            maxTailLosses = System.getenv("MAX_TAIL_LOSSES")?.toIntOrNull() ?: base.maxTailLosses,
            pauseMs = System.getenv("PAUSE_MS")?.toLongOrNull() ?: base.pauseMs,
            logSignals = System.getenv("LOG_SIGNALS")?.toBooleanStrictOrNull() ?: base.logSignals
        )
    }

    private fun writeVacuumSummary(
        config: VacuumConfig,
        summary: VacuumKpiSummary,
        telemetryPath: String?,
        manifestPath: String?,
        mode: String
    ) {
        val configs = mapOf(
            "symbol" to config.symbol,
            "depth_drop_pct" to config.depthDropPct.toString(),
            "spread_widen_pct" to config.spreadWidenPct.toString(),
            "max_hold_ms" to config.maxHoldMs.toString()
        ).filterValues { it.isNotBlank() }
        RunSummaryWriter.writeSummary(
            root = findProjectRoot(),
            summary = RunSummary(
                strategy = "vacuum",
                mode = mode,
                timestampMs = System.currentTimeMillis(),
                configs = configs,
                metrics = mapOf(
                    "avg_slippage_bps" to summary.avgSlippageBps,
                    "avg_adverse_move_bps" to summary.avgAdverseMoveBps,
                    "tail_loss_count" to summary.tailLossCount
                ),
                health = mapOf(
                    "last_slippage_bps" to summary.lastSlippageBps,
                    "cancel_rate" to summary.cancelRate,
                    "stale_cancel_rate" to summary.staleCancelRate
                ),
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
