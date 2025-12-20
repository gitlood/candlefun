package com.example

import com.example.algo.AlgoConfigSweeps
import com.example.algo.ProfitBotRow
import com.example.algo.ProfitBotSweepReport
import com.example.algo.SweepReportConfig
import com.example.algo.backtest.SignalBacktester
import com.example.algo.priortoprofitgroups.EventStudyAnalyzer
import com.example.algo.profitgroups.HistoricProfitGroupFinder
import com.example.algo.profitgroups.ProfitGroupQualityGate
import com.example.historicaldata.HistoricalDataRepository
import com.example.platformutil.ACTIVE_BOTS_FILE_NAME
import com.example.platformutil.AlgoConfig
import com.example.platformutil.model.BotSpec
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import kotlin.math.min

private data class Candidate(
    val cfg: AlgoConfig,
    val pattern: String,
    val trades: Int,
    val tradesPerDay: Double,
    val winRate: Double,
    val avgNet: Double,
    val medNet: Double,
    val sumNet: Double,
    val compNet: Double,
    val tp: Double,
    val sl: Double,
    val hz: Int,
)

fun main() {
    println("Fetching 5-minute ETH/USDT candles from DB...")
    val repo = HistoricalDataRepository.create()
    val allCandlesRaw = repo.getAllCandles()
    println("Loaded ${allCandlesRaw.size} candles.")
    if (allCandlesRaw.isEmpty()) return

    // ✅ Candle uses openTime/closeTime (no timestamp field)
    val allCandles = allCandlesRaw.sortedBy { it.openTime }

    val msPerDay = 86_400_000.0
    val firstTime = allCandles.first().openTime
    val lastTime = allCandles.last().openTime
    val sampleDays = ((lastTime - firstTime) / msPerDay).coerceAtLeast(1.0)

    println("Sample span ≈ %.1f days".format(sampleDays))

    // ========= TARGETS =========
    val targetBotCount = 50

    // ========= EXPLORATION FILTERS (keep loose if you want 50 bots) =========
    val minTradesFloor = 10
    val requireProfitableForSelection = true

    // Per-config shortlisting (prevents memory blowups across huge grids)
    val shortlistTopProfitPerConfig = 8
    val shortlistTopVolumePerConfig = 8
    val shortlistTopStablePerConfig = 8

    // Optional: cap configs for debugging (set to e.g. 200 to test quickly)
    val maxConfigsToRun: Int? = null

    val base = AlgoConfig()

    val configs = AlgoConfigSweeps.grid(
        base = base,

        // Backtest knobs
        takeProfits = listOf(0.005, 0.008, 0.012, 0.016),
        stopLosses = listOf(0.003, 0.004, 0.006, 0.008),
        horizonsMinutes = listOf(30, 60, 90),
        maxDrawdowns = listOf(0.05, 0.10),
        localLowLookbacks = listOf(0, 2, 3, 5),

        // RuleGate knobs (affects frequency heavily)
        ret30mMins = listOf(-0.02, -0.01, -0.005),
        volumeZMins = listOf(-1.0, -0.5, 0.0),
        contractionMaxes = listOf(1.05, 1.10, 1.20),
        trendSlopeMins = listOf(0.0)
    )

    val candidateMap = LinkedHashMap<String, Candidate>()

    fun keyOf(cfg: AlgoConfig, pattern: String): String = "${cfg.id()}||$pattern"

    fun addShortlist(list: List<Candidate>) {
        for (c in list) {
            val k = keyOf(c.cfg, c.pattern)
            val prev = candidateMap[k]
            if (prev == null || c.compNet > prev.compNet) {
                candidateMap[k] = c
            }
        }
    }

    configs.forEachIndexed { i, cfg ->
        if (maxConfigsToRun != null && i >= maxConfigsToRun) {
            println("Reached maxConfigsToRun=$maxConfigsToRun, stopping early.")
            return@forEachIndexed
        }

        println("\n============================================================")
        println("RUN #${i + 1}/${configs.count()}: ${cfg.id()}")
        println("============================================================")

        val finder = HistoricProfitGroupFinder(allCandles)
        val breakoutGroups = finder.findAndReport(cfg)

        val gate = ProfitGroupQualityGate.decide(cfg, breakoutGroups)
        if (!gate.proceed) {
            println("SKIP config: ${gate.reason}")
            return@forEachIndexed
        }

        val fullKeys =
            EventStudyAnalyzer.runAndGetTopFullKeysByLift(allCandles, breakoutGroups, cfg)
        if (fullKeys.isEmpty()) {
            println("No patterns returned. Skipping.")
            return@forEachIndexed
        }

        // MUST match live: RuleGate ON
        val rows = SignalBacktester.runPatternBacktests(
            candlesRaw = allCandles,
            patterns = fullKeys,
            cfg = cfg,
            useRuleGate = true,
            preferSeqOnly = true
        )

        if (rows.isEmpty()) {
            println("Backtester returned 0 rows.")
            return@forEachIndexed
        }

        println("Total patterns tested: ${rows.size}")

        val candidates = rows
            .asSequence()
            .filter { it.trades >= minTradesFloor }
            .map { r ->
                Candidate(
                    cfg = cfg,
                    pattern = r.pattern,
                    trades = r.trades,
                    tradesPerDay = r.tradesPerDay,
                    winRate = r.winRate,
                    avgNet = r.avgNet,
                    medNet = r.medNet,
                    sumNet = r.sumNet,
                    compNet = r.compNet,
                    tp = r.tp.toDouble(),
                    sl = r.sl.toDouble(),
                    hz = r.hz
                )
            }
            .toList()

        if (candidates.isEmpty()) {
            println("No candidates after minTradesFloor=$minTradesFloor")
            return@forEachIndexed
        }

        val best = candidates.maxByOrNull { it.compNet }
        if (best != null) {
            println(
                "Best this config: compNet=%.4f trades=%d tpd=%.2f med=%.4f win=%.1f%% pattern=%s"
                    .format(
                        best.compNet,
                        best.trades,
                        best.tradesPerDay,
                        best.medNet,
                        best.winRate * 100.0,
                        best.pattern
                    )
            )
        }

        val topProfit = candidates
            .sortedByDescending { it.compNet }
            .take(shortlistTopProfitPerConfig)

        val topVolumeProfitable = candidates
            .filter { it.compNet > 0.0 }
            .sortedByDescending { it.trades }
            .take(shortlistTopVolumePerConfig)

        val topStableProfitable = candidates
            .filter { it.compNet > 0.0 && it.medNet > 0.0 }
            .sortedWith(compareByDescending<Candidate> { it.compNet }.thenByDescending { it.trades })
            .take(shortlistTopStablePerConfig)

        addShortlist(topProfit)
        addShortlist(topVolumeProfitable)
        addShortlist(topStableProfitable)

        println(
            "Shortlisted this config: " +
                    "profit=${topProfit.size}, " +
                    "volumeProf=${topVolumeProfitable.size}, " +
                    "stableProf=${topStableProfitable.size} " +
                    " | globalUnique=${candidateMap.size}"
        )
    }

    val allCandidates = candidateMap.values.toList()
    println("\n============================================================")
    println("GLOBAL CANDIDATES: ${allCandidates.size}")
    println("============================================================")

    if (allCandidates.isEmpty()) {
        println("No candidates found. Try lowering RuleGate strictness or reducing minTradesFloor.")
        return
    }

    val finalPool = if (requireProfitableForSelection) {
        allCandidates.filter { it.compNet > 0.0 }
    } else {
        allCandidates
    }

    println("Final selection pool size: ${finalPool.size} (requireProfitable=$requireProfitableForSelection)")

    if (finalPool.isEmpty()) {
        println("Pool is empty after profitability filter. Set requireProfitableForSelection=false or loosen RuleGate.")
        return
    }

    fun normalize(x: Double, lo: Double, hi: Double): Double {
        if (hi <= lo) return 0.0
        return ((x - lo) / (hi - lo)).coerceIn(0.0, 1.0)
    }

    val minComp = finalPool.minOf { it.compNet }
    val maxComp = finalPool.maxOf { it.compNet }
    val minTrades = finalPool.minOf { it.trades }.toDouble()
    val maxTrades = finalPool.maxOf { it.trades }.toDouble()

    fun balancedScore(c: Candidate): Double {
        val p = normalize(c.compNet, minComp, maxComp)
        val t = normalize(c.trades.toDouble(), minTrades, maxTrades)
        return 0.65 * p + 0.35 * t
    }

    // Quotas (remainder filled by balanced score)
    val quotaTopProfit = 20
    val quotaTopVolume = 15
    val quotaStableProfitable = 10

    val selected = LinkedHashMap<String, Candidate>()

    fun putIfRoom(c: Candidate) {
        if (selected.size >= targetBotCount) return
        selected.putIfAbsent(keyOf(c.cfg, c.pattern), c)
    }

    finalPool.sortedByDescending { it.compNet }
        .take(quotaTopProfit)
        .forEach(::putIfRoom)

    finalPool.sortedByDescending { it.trades }
        .take(quotaTopVolume)
        .forEach(::putIfRoom)

    finalPool.asSequence()
        .filter { it.medNet > 0.0 }
        .sortedWith(compareByDescending<Candidate> { it.compNet }.thenByDescending { it.trades })
        .take(quotaStableProfitable)
        .forEach(::putIfRoom)

    if (selected.size < targetBotCount) {
        finalPool.asSequence()
            .sortedByDescending { balancedScore(it) }
            .filter { !selected.containsKey(keyOf(it.cfg, it.pattern)) }
            .take(targetBotCount - selected.size)
            .forEach { selected[keyOf(it.cfg, it.pattern)] = it }
    }

    val finalSelected = selected.values.take(targetBotCount)

    println("\n============================================================")
    println("SELECTED BOTS: ${finalSelected.size} / $targetBotCount")
    println("============================================================")

    val allProfitBots = finalSelected.map { c ->
        ProfitBotRow(
            cfg = c.cfg,
            pattern = c.pattern,
            tpPct = c.cfg.backtest.takeProfit * 100.0,
            trades = c.trades,
            winRate = c.winRate,
            avgNet = c.avgNet,
            medNet = c.medNet,
            sumNet = c.sumNet,
            compNet = c.compNet,
            tp = c.tp.toInt(),
            sl = c.sl.toInt(),
            hz = c.hz
        )
    }

    ProfitBotSweepReport.print(
        all = allProfitBots,
        cfg = SweepReportConfig(
            minTradesPerBot = minTradesFloor,
            minCompNet = if (requireProfitableForSelection) 0.0 else -999.0,
            topNOverall = min(100, allProfitBots.size),
            topNPerConfig = 3
        )
    )

    val botSpecs = allProfitBots.map { row ->
        BotSpec(
            name = "ethbot-${row.cfg.id()}-${row.pattern.replace("=", "_")}",
            patterns = setOf(row.pattern),
            cfg = row.cfg
        )
    }

    val mapper = jacksonObjectMapper()
        .findAndRegisterModules()
        .enable(SerializationFeature.INDENT_OUTPUT)

    val outputFile = File(ACTIVE_BOTS_FILE_NAME)
    mapper.writeValue(outputFile, botSpecs)

    println("\nSuccessfully exported ${botSpecs.size} bots to ${outputFile.absolutePath}")
    println("Selection rules:")
    println(" - minTradesFloor=$minTradesFloor")
    println(" - requireProfitableForSelection=$requireProfitableForSelection")
    println(" - quotas: topProfit=$quotaTopProfit, topVolume=$quotaTopVolume, stableProfitable=$quotaStableProfitable, remainder=balancedScore")
}
