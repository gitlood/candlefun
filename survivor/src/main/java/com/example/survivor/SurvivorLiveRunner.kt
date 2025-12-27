package com.example.survivor

import com.example.platform.report.ExperimentManifest
import com.example.platform.report.ExperimentManifestWriter
import com.example.platform.report.Telemetry
import kotlinx.coroutines.runBlocking
import java.io.File

object SurvivorLiveRunner {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        Telemetry.configureFromEnv("survivor_live")
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
        val gates = SurvivorGateStats()
        val gateway = SurvivorPaperGateway { fill -> kpi.onFill(fill) }
        val strategy = SurvivorStrategy(gateway, config, kpi, gates)
        val tailer = SurvivorCsvTailer(file, pollMs = pollMs)
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
                reportPath = null,
                runId = System.getenv("RUN_ID"),
                notes = System.getenv("RUN_NOTES")
            )
        )

        var lastKpiMs = 0L
        tailer.stream().collect { snap ->
            strategy.onSnapshot(snap)
            val now = snap.timestampMs
            if (lastKpiMs == 0L) lastKpiMs = now
            if (now - lastKpiMs >= logEveryMs) {
                SurvivorReport.print(kpi.summary(), "SURVIVOR LIVE KPI")
                println(gates.report())
                lastKpiMs = now
            }
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

}
