package com.example.survivor

import com.example.platform.report.ExperimentManifest
import com.example.platform.report.ExperimentManifestWriter
import com.example.platform.report.RunSummary
import com.example.platform.report.RunSummaryWriter
import com.example.platform.report.Telemetry
import com.example.execution.impl.ExecutionPolicy
import com.example.execution.impl.IntentAllocator
import com.example.execution.impl.RiskBudgetEnv
import kotlinx.coroutines.runBlocking
import java.io.File

object SurvivorLiveRunner {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        Telemetry.configureFromEnv("survivor_live", defaultEnabled = true)
        val inputPath = args.getOrNull(0)
            ?: System.getenv("SURVIVOR_TAIL_CSV")
            ?: defaultPath()
        val pollMs = System.getenv("TAIL_POLL_MS")?.toLongOrNull() ?: 1_000L
        val logEveryMs = System.getenv("LOG_KPI_EVERY_MS")?.toLongOrNull() ?: 60_000L

        val file = File(inputPath)
        println("Survivor live tail: ${file.absolutePath}")
        println("Exists            : ${file.exists()} sizeBytes=${if (file.exists()) file.length() else 0L}")

        val config = configFromEnv()
        val kpi = SurvivorKpiTracker(config)
        val gateway = SurvivorPaperGateway { fill -> kpi.onFill(fill) }
        val strategy = SurvivorIntentStrategy(config)
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
            heartbeatMs = config.rebalanceIntervalMs,
            staleFeedMs = config.staleFeedMs
        )
        val tailer = SurvivorCsvTailer(file, pollMs = pollMs)
        val telemetryPath = Telemetry.resolveReportPathFromEnv("survivor_live", defaultEnabled = true)
        val manifestWriter = ExperimentManifestWriter.fromEnv()
        manifestWriter?.write(
            ExperimentManifest(
                timestampMs = System.currentTimeMillis(),
                strategy = "survivor",
                mode = "live",
                symbols = listOf(config.symbol),
                params = mapOf(
                    "SURVIVOR_TAIL_CSV" to inputPath,
                    "TAIL_POLL_MS" to pollMs.toString()
                ).filterValues { it.isNotBlank() },
                reportPath = telemetryPath,
                runId = System.getenv("RUN_ID"),
                notes = System.getenv("RUN_NOTES")
            )
        )

        var lastKpiMs = 0L
        var heartbeatJob: kotlinx.coroutines.Job? = null
        try {
            heartbeatJob = engine.startHeartbeat(this)
            tailer.stream().collect { snap ->
                engine.onSnapshot(snap)
                val now = snap.timestampMs
                if (lastKpiMs == 0L) lastKpiMs = now
                if (now - lastKpiMs >= logEveryMs) {
                    SurvivorReport.print(kpi.summary(), "SURVIVOR LIVE KPI")
                    lastKpiMs = now
                }
            }
        } finally {
            heartbeatJob?.cancel()
            val summary = kpi.summary()
            SurvivorReport.print(summary, "SURVIVOR LIVE KPI")
            writeSurvivorLiveSummary(
                config = config,
                summary = summary,
                telemetryPath = telemetryPath,
                manifestPath = manifestWriter?.path(),
                mode = "live"
            )
        }
    }

    private fun defaultPath(): String {
        val root = findProjectRoot()
        return File(root, "survivor_live.csv").absolutePath
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

    private fun writeSurvivorLiveSummary(
        config: SurvivorConfig,
        summary: SurvivorKpiSummary,
        telemetryPath: String?,
        manifestPath: String?,
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
