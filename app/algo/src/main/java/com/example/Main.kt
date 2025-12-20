package com.example

import com.example.algo.AlgoConfigSweeps
import com.example.algo.backtest.SignalBacktester
import com.example.platformutil.AlgoConfig
import com.example.algo.ProfitBotRow
import com.example.algo.ProfitBotSweepReport
import com.example.algo.SweepReportConfig
import com.example.algo.priortoprofitgroups.EventStudyAnalyzer
import com.example.algo.profitgroups.HistoricProfitGroupFinder
import com.example.algo.profitgroups.ProfitGroupQualityGate
import com.example.historicaldata.HistoricalDataRepository
import com.example.platformutil.ACTIVE_BOTS_FILE_NAME
import com.example.platformutil.model.BotSpec
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File

fun main() {
    println("Fetching candles from DB...")
    val repo = HistoricalDataRepository.create()
    val allCandles = repo.getAllCandles()
    println("Loaded ${allCandles.size} candles.")

    if (allCandles.isEmpty()) return

    val base = AlgoConfig()

    val configs = AlgoConfigSweeps.grid(
        base = base,
        takeProfits = listOf(0.02, 0.03, 0.05),
        stopLosses = listOf(0.01, 0.015, 0.02),
        horizonsMinutes = listOf(30, 60, 120),
        maxDrawdowns = listOf(0.10, 0.20),
        localLowLookbacks = listOf(0, 5),
    )

    val allProfitBots = mutableListOf<ProfitBotRow>()

    configs.forEachIndexed { i, cfg ->
        println("\n============================================================")
        println("RUN #${i + 1}: ${cfg.id()}")
        println("============================================================")

        val finder = HistoricProfitGroupFinder(allCandles)
        val breakoutGroups = finder.findAndReport(cfg)

        val gate = ProfitGroupQualityGate.decide(cfg, breakoutGroups)
        if (!gate.proceed) {
            println("SKIP config: ${gate.reason}")
            return@forEachIndexed
        }

        val fullKeys = EventStudyAnalyzer.runAndGetTopFullKeysByLift(allCandles, breakoutGroups, cfg)
        if (fullKeys.isEmpty()) {
            println("No patterns returned. Skipping.")
            return@forEachIndexed
        }

        val rows = SignalBacktester.runPatternBacktests(
            candlesRaw = allCandles,
            patterns = fullKeys,
            cfg = cfg
        )

        // collect profitable “bots” (pattern + cfg)
        val tpPct = cfg.backtest.takeProfit * 100.0
        rows.filter { it.compNet > 0.0 && it.trades > 0 }.forEach { r ->
            allProfitBots += ProfitBotRow(
                cfg = cfg,
                pattern = r.pattern,
                tpPct = tpPct,
                trades = r.trades,
                winRate = r.winRate,
                avgNet = r.avgNet,
                medNet = r.medNet,
                sumNet = r.sumNet,
                compNet = r.compNet,
                tp = r.tp,
                sl = r.sl,
                hz = r.hz,
            )
        }
    }

    // One big report at the end
    ProfitBotSweepReport.print(
        all = allProfitBots,
        cfg = SweepReportConfig(
            minTradesPerBot = 20,
            minCompNet = 0.0,
            topNOverall = 100,
            topNPerConfig = 3
        )
    )

    // After ProfitBotSweepReport.print(...)

    // 1. Transform ProfitBotRows into BotSpec objects
    val botSpecs = allProfitBots
        .filter { it.trades >= 20 && it.compNet > 0.05 } // Optional: strict filter for JSON export
        .map { row ->
            BotSpec(
                name = "bot-${row.cfg.id()}-${row.pattern.replace("=", "_")}",
                patterns = setOf(row.pattern),
                cfg = row.cfg
            )
        }

    // 2. Serialize to JSON
    val mapper = jacksonObjectMapper()
        .findAndRegisterModules() // <--- Added this to fix JSR310 error
        .enable(SerializationFeature.INDENT_OUTPUT)

    val outputFile = File(ACTIVE_BOTS_FILE_NAME)
    mapper.writeValue(outputFile, botSpecs)

    println("\nSuccessfully exported ${botSpecs.size} bots to ${outputFile.absolutePath}")
}
