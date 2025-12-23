package com.example

import com.example.algo.AlgoConfigSweeps
import com.example.algo.ProfitBotRow
import com.example.algo.ProfitBotSweepReport
import com.example.algo.SweepReportConfig
import com.example.algo.backtest.SignalBacktester
import com.example.algo.priortoprofitgroups.EventStudyAnalyzer
import com.example.algo.model.BreakoutGroup
import com.example.algo.profitgroups.HistoricProfitGroupFinder
import com.example.algo.profitgroups.ProfitGroupQualityGate
import com.example.historicaldata.HistoricalDataRepository
import com.example.historicaldata.interfaces.OrderBookRepository
import com.example.platformutil.ACTIVE_BOTS_FILE_NAME
import com.example.platformutil.AlgoConfig
import com.example.platformutil.ProfitGroupMode
import com.example.platformutil.orderBookDbPath
import com.example.platformutil.resolveCandleDbPath
import com.example.platformutil.intervalToMillis
import com.example.platformutil.model.BotSpec
import com.example.platformutil.model.Candle
import com.example.platformutil.model.OrderBookSnapshot
import com.example.platformutil.model.TradingConfig
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.io.OutputStream
import java.io.PrintWriter
import java.io.PrintStream
import java.time.Instant
import java.util.Locale
import kotlin.math.min
import kotlinx.coroutines.*
import kotlinx.coroutines.ExperimentalCoroutinesApi

private data class Candidate(
    val cfg: AlgoConfig,
    val pattern: String,
    val patterns: Set<String> = setOf(pattern),
    val train: SignalBacktester.PatternBacktestRow,
    val validation: SignalBacktester.PatternBacktestRow,
    val test: SignalBacktester.PatternBacktestRow,
    val eventStudy: EventStudyAnalyzer.PatternRow? = null
)

private data class BreakoutStats(
    val total: Int,
    val avgGain: Double,
    val avgHorizonGain: Double,
    val avgNet: Double,
    val avgMaxDd: Double,
    val bestGain: Double,
    val worstGain: Double,
    val thresholdCounts: Map<Double, Int>
)

private data class ConfigResult(
    val cfg: AlgoConfig,
    val candidates: List<Candidate>,
    val bestTrainRows: List<SignalBacktester.PatternBacktestRow>,
    val eventStudyResult: EventStudyAnalyzer.EventStudyResult?,
    val qualityGatePassed: Boolean,
    val qualityGateReason: String?,
    val breakoutStats: BreakoutStats?
)

@OptIn(ExperimentalCoroutinesApi::class)
fun main(args: Array<String>) {
    val logLimit = args.firstOrNull { it.startsWith("--log-limit=") }
        ?.substringAfter("=")
        ?.toIntOrNull() ?: 1000
    val reportCsvPath = args.firstOrNull { it.startsWith("--report-csv=") }
        ?.substringAfter("=")
        ?: "algo_last_run.csv"
    installLogLimiter(logLimit)
    val runStartMs = System.currentTimeMillis()
    val runStartedAt = Instant.now()
    val runId = runStartedAt.toString()
    val reportCsvFile = File(reportCsvPath)
    println("Algo run report CSV: ${reportCsvFile.absolutePath}")

    val maxConfigsArg = args.firstOrNull { it.startsWith("--max-configs=") }
        ?.substringAfter("=")
        ?.toIntOrNull()
    val maxConfigsEnv = System.getenv("MAX_CONFIGS")?.toIntOrNull()
    val maxConfigsToRunArg = maxConfigsArg ?: maxConfigsEnv

    val maxGroupsPrintArg = args.firstOrNull { it.startsWith("--max-groups=") }
        ?.substringAfter("=")
        ?.toIntOrNull()
    val maxGroupsPrintEnv = System.getenv("MAX_GROUPS_PRINT")?.toIntOrNull()
    val maxGroupsToPrint = maxGroupsPrintArg ?: maxGroupsPrintEnv ?: 0

    val symbol = args.firstOrNull { it.startsWith("--symbol=") }?.substringAfter("=") ?: "ETHUSDT"
    val interval = args.firstOrNull { it.startsWith("--interval=") }?.substringAfter("=") ?: "5m"
    val intervalMillis = intervalToMillis(interval)
    val verbose = args.firstOrNull { it.startsWith("--verbose=") }
        ?.substringAfter("=")
        ?.toBooleanStrictOrNull() ?: false

    val splitArg = args.firstOrNull { it.startsWith("--walk-forward=") }?.substringAfter("=")
        ?: "0.7,0.15,0.15"
    val split = parseSplit(splitArg)

    val minTradesFloor = args.firstOrNull { it.startsWith("--min-trades=") }
        ?.substringAfter("=")
        ?.toIntOrNull() ?: 10
    val oosMinTrades = args.firstOrNull { it.startsWith("--oos-min-trades=") }
        ?.substringAfter("=")
        ?.toIntOrNull() ?: 5
    val oosMinComp = args.firstOrNull { it.startsWith("--oos-min-comp=") }
        ?.substringAfter("=")
        ?.toDoubleOrNull() ?: 0.0
    val oosMinMed = args.firstOrNull { it.startsWith("--oos-min-med=") }
        ?.substringAfter("=")
        ?.toDoubleOrNull() ?: 0.0

    val orderBookEnabled = args.firstOrNull { it.startsWith("--orderbook-enabled=") }
        ?.substringAfter("=")
        ?.toBooleanStrictOrNull() ?: false
    val orderBookImbalanceList = parseDoubleList(
        args.firstOrNull { it.startsWith("--orderbook-imb10=") }?.substringAfter("=")
    ).ifEmpty { listOf(0.05) }
    val orderBookSpreadList = parseDoubleList(
        args.firstOrNull { it.startsWith("--orderbook-spread=") }?.substringAfter("=")
    ).ifEmpty { listOf(15.0) }

    val eventTopK = args.firstOrNull { it.startsWith("--event-topk=") }
        ?.substringAfter("=")
        ?.toIntOrNull()
    val eventMinPos = args.firstOrNull { it.startsWith("--event-min-pos=") }
        ?.substringAfter("=")
        ?.toIntOrNull()
    val eventPatternBars = args.firstOrNull { it.startsWith("--event-pattern-bars=") }
        ?.substringAfter("=")
        ?.toIntOrNull()
    val eventContextBars = args.firstOrNull { it.startsWith("--event-context-bars=") }
        ?.substringAfter("=")
        ?.toIntOrNull()
    val eventNegEvery = args.firstOrNull { it.startsWith("--event-neg-every=") }
        ?.substringAfter("=")
        ?.toIntOrNull()
    val eventMinPosEvents = args.firstOrNull { it.startsWith("--event-min-pos-events=") }
        ?.substringAfter("=")
        ?.toIntOrNull()
    val eventMinNegSamples = args.firstOrNull { it.startsWith("--event-min-neg-samples=") }
        ?.substringAfter("=")
        ?.toIntOrNull()
    val eventMinDistinctFull = args.firstOrNull { it.startsWith("--event-min-distinct=") }
        ?.substringAfter("=")
        ?.toIntOrNull()
    val eventMinNet = args.firstOrNull { it.startsWith("--event-min-net=") }
        ?.substringAfter("=")
        ?.toDoubleOrNull()
    val eventEmbargoMin = args.firstOrNull { it.startsWith("--event-embargo-min=") }
        ?.substringAfter("=")
        ?.toIntOrNull()
    val eventStabilityFolds = args.firstOrNull { it.startsWith("--event-stability-folds=") }
        ?.substringAfter("=")
        ?.toIntOrNull()
    val eventStableMinFolds = args.firstOrNull { it.startsWith("--event-stable-min-folds=") }
        ?.substringAfter("=")
        ?.toIntOrNull()
    val eventStableMinPos = args.firstOrNull { it.startsWith("--event-stable-min-pos=") }
        ?.substringAfter("=")
        ?.toIntOrNull()
    val eventMaxFdr = args.firstOrNull { it.startsWith("--event-max-fdr=") }
        ?.substringAfter("=")
        ?.toDoubleOrNull()
    val eventRegimeMinBuckets = args.firstOrNull { it.startsWith("--event-regime-min-buckets=") }
        ?.substringAfter("=")
        ?.toIntOrNull()
    val eventRegimeMinPos = args.firstOrNull { it.startsWith("--event-regime-min-pos=") }
        ?.substringAfter("=")
        ?.toIntOrNull()

    val eventDefaults = AlgoConfig().eventStudy
    val eventStudy = eventDefaults.copy(
        topK = eventTopK ?: eventDefaults.topK,
        minPosCount = eventMinPos ?: eventDefaults.minPosCount,
        patternBars = eventPatternBars ?: eventDefaults.patternBars,
        contextBars = eventContextBars ?: eventDefaults.contextBars,
        negativeSampleEveryN = eventNegEvery ?: eventDefaults.negativeSampleEveryN,
        minNetEdge = eventMinNet ?: eventDefaults.minNetEdge,
        embargoMinutes = eventEmbargoMin ?: eventDefaults.embargoMinutes,
        stabilityFolds = eventStabilityFolds ?: eventDefaults.stabilityFolds,
        minStableFolds = eventStableMinFolds ?: eventDefaults.minStableFolds,
        minPosPerFold = eventStableMinPos ?: eventDefaults.minPosPerFold,
        maxFdr = eventMaxFdr ?: eventDefaults.maxFdr,
        regimeMinBuckets = eventRegimeMinBuckets ?: eventDefaults.regimeMinBuckets,
        regimeMinPosPerBucket = eventRegimeMinPos ?: eventDefaults.regimeMinPosPerBucket,
        minPosEventsToRun = eventMinPosEvents ?: eventDefaults.minPosEventsToRun,
        minNegSamplesToRun = eventMinNegSamples ?: eventDefaults.minNegSamplesToRun,
        minDistinctFullKeysToRun = eventMinDistinctFull ?: eventDefaults.minDistinctFullKeysToRun,
        printReport = verbose
    )

    val confluenceEnabledArg = args.firstOrNull { it.startsWith("--confluence=") }
        ?.substringAfter("=")
        ?.toBooleanStrictOrNull()
    val confluenceEnabled = confluenceEnabledArg ?: args.contains("--confluence")
    val confluenceLookback = args.firstOrNull { it.startsWith("--confluence-lookback=") }
        ?.substringAfter("=")
        ?.toIntOrNull()
    val confluenceMinMatches = args.firstOrNull { it.startsWith("--confluence-min=") }
        ?.substringAfter("=")
        ?.toIntOrNull()
    val confluenceBuffer = args.firstOrNull { it.startsWith("--confluence-buffer=") }
        ?.substringAfter("=")
        ?.toDoubleOrNull()
    val confluenceCloseAbove = args.firstOrNull { it.startsWith("--confluence-close-above=") }
        ?.substringAfter("=")
        ?.toBooleanStrictOrNull()

    val useWalkForward = !args.contains("--no-walkforward")
    val useQualityGate = !args.contains("--no-quality-gate")

    val profitModeArg = args.firstOrNull { it.startsWith("--profit-mode=") }?.substringAfter("=")
    val profitMode = when (profitModeArg?.lowercase()) {
        null -> AlgoConfig().profitGroup.mode
        "tp", "tp-hit", "tp_hit" -> ProfitGroupMode.TP_HIT
        "net", "net-positive", "net_positive" -> ProfitGroupMode.NET_POSITIVE
        else -> {
            println("Unknown --profit-mode=$profitModeArg, defaulting to TP_HIT.")
            ProfitGroupMode.TP_HIT
        }
    }
    if (profitModeArg != null) {
        println("ProfitGroup mode override: $profitMode")
    }

    val dbPath = resolveCandleDbPath(symbol, interval)

    println("Fetching $interval $symbol candles from DB ($dbPath)...")
    val repo = HistoricalDataRepository.create(dbPath)
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

    val embargoBars = minutesToBars(eventStudy.embargoMinutes, intervalMillis)
    val (trainCandles, valCandles, testCandles) = if (useWalkForward) {
        val splitResult = splitCandles(allCandles, split, embargoBars)
        val embargoMsg = if (embargoBars > 0) " (embargoBars=$embargoBars)" else ""
        println(
            "Walk-forward split: train=${splitResult.first.size}, val=${splitResult.second.size}, " +
                "test=${splitResult.third.size}$embargoMsg"
        )
        splitResult
    } else {
        Triple(allCandles, allCandles, allCandles)
    }

    // already logged inside useWalkForward branch above

    val rawOrderBookSnapshots = if (orderBookEnabled) {
        loadOrderBookSnapshots(symbol, allCandles.first().openTime)
    } else {
        emptyList()
    }
    val orderBookEnabledEffective = orderBookEnabled && rawOrderBookSnapshots.isNotEmpty()
    if (orderBookEnabled && rawOrderBookSnapshots.isEmpty()) {
        println("OrderBook enabled but no snapshots found for $symbol. Disabling orderbook gating.")
    }
    val orderBookSnapshots = rawOrderBookSnapshots

    // ========= TARGETS =========
    val targetBotCount = 50

    // ========= EXPLORATION FILTERS (keep loose if you want 50 bots) =========
    val requireProfitableForSelection = true
    val quotaTopProfit = 20
    val quotaTopVolume = 15
    val quotaStableProfitable = 10

    // Per-config shortlisting (prevents memory blowups across huge grids)
    val shortlistTopProfitPerConfig = 8
    val shortlistTopVolumePerConfig = 8
    val shortlistTopStablePerConfig = 8

    // Optional: cap configs for debugging (set to e.g. 200 to test quickly)
    val maxConfigsToRun: Int? = maxConfigsToRunArg

    val base = AlgoConfig(
        backtest = AlgoConfig().backtest.copy(intervalMillis = intervalMillis),
        profitGroup = AlgoConfig().profitGroup.copy(
            maxGroupsToPrint = maxGroupsToPrint,
            printReport = verbose,
            mode = profitMode
        ),
        eventStudy = eventStudy,
        orderBook = AlgoConfig().orderBook.copy(enabled = orderBookEnabledEffective),
        confluence = AlgoConfig().confluence.copy(
            enabled = confluenceEnabled,
            lookbackMinutes = confluenceLookback ?: AlgoConfig().confluence.lookbackMinutes,
            minMatches = confluenceMinMatches ?: AlgoConfig().confluence.minMatches,
            breakoutBufferPct = confluenceBuffer ?: AlgoConfig().confluence.breakoutBufferPct,
            breakoutRequireCloseAbove = confluenceCloseAbove ?: AlgoConfig().confluence.breakoutRequireCloseAbove
        )
    )

    if (!useQualityGate) {
        println("ProfitGroupQualityGate disabled (--no-quality-gate).")
    }
    if (
        eventTopK != null || eventMinPos != null || eventPatternBars != null || eventContextBars != null ||
        eventNegEvery != null || eventMinPosEvents != null || eventMinNegSamples != null ||
        eventMinDistinctFull != null || eventMinNet != null || eventEmbargoMin != null ||
        eventStabilityFolds != null || eventStableMinFolds != null || eventStableMinPos != null ||
        eventMaxFdr != null || eventRegimeMinBuckets != null || eventRegimeMinPos != null
    ) {
        println(
            "EventStudy overrides: topK=${eventStudy.topK}, minPos=${eventStudy.minPosCount}, " +
                "patternBars=${eventStudy.patternBars}, contextBars=${eventStudy.contextBars}, " +
                "negEvery=${eventStudy.negativeSampleEveryN}, minNet=${eventStudy.minNetEdge}, " +
                "embargoMin=${eventStudy.embargoMinutes}, folds=${eventStudy.stabilityFolds}, " +
                "minStableFolds=${eventStudy.minStableFolds}, minPosPerFold=${eventStudy.minPosPerFold}, " +
                "maxFdr=${eventStudy.maxFdr}, regimeMinBuckets=${eventStudy.regimeMinBuckets}, " +
                "regimeMinPos=${eventStudy.regimeMinPosPerBucket}, " +
                "minPosEvents=${eventStudy.minPosEventsToRun}, minNegSamples=${eventStudy.minNegSamplesToRun}, " +
                "minDistinctFull=${eventStudy.minDistinctFullKeysToRun}"
        )
    }

    if (confluenceEnabled) {
        println(
            "Confluence enabled: lookback=${confluenceLookback ?: AlgoConfig().confluence.lookbackMinutes}m (0=use horizon) " +
                "minMatches=${confluenceMinMatches ?: AlgoConfig().confluence.minMatches} " +
                "buffer=${confluenceBuffer ?: AlgoConfig().confluence.breakoutBufferPct} " +
                "closeAbove=${confluenceCloseAbove ?: AlgoConfig().confluence.breakoutRequireCloseAbove}"
        )
    }

    val takeProfits = listOf(0.005, 0.008, 0.012, 0.016)
    val stopLosses = listOf(0.003, 0.004, 0.006, 0.008)
    val horizonsMinutes = listOf(30, 60, 90)
    val maxDrawdowns = listOf(0.05, 0.10)
    val localLowLookbacks = listOf(0, 2, 3, 5)
    val ret30mMins = listOf(-0.06, -0.05, -0.04, -0.03, -0.02, -0.01, -0.005)
    val volumeZMins = listOf(-2.0, -1.5, -1.0, -0.5, 0.0)
    val contractionMaxes = listOf(1.05, 1.10, 1.20)
    val trendSlopeMins = listOf(0.0)

    val configs = AlgoConfigSweeps.grid(
        base = base,

        // Backtest knobs
        takeProfits = takeProfits,
        stopLosses = stopLosses,
        horizonsMinutes = horizonsMinutes,
        maxDrawdowns = maxDrawdowns,
        localLowLookbacks = localLowLookbacks,

        // RuleGate knobs (affects frequency heavily)
        ret30mMins = ret30mMins,
        volumeZMins = volumeZMins,
        contractionMaxes = contractionMaxes,
        trendSlopeMins = trendSlopeMins,
        orderBookEnableds = listOf(orderBookEnabledEffective),
        orderBookMinImbalance10s = orderBookImbalanceList,

        orderBookMaxSpreadBps = orderBookSpreadList
    ).toList()

    val enforceOos = useWalkForward
    val parallelism = args.firstOrNull { it.startsWith("--parallelism=") }
        ?.substringAfter("=")
        ?.toIntOrNull()
        ?.coerceAtLeast(1) ?: Runtime.getRuntime().availableProcessors()

    val allResults = runBlocking {
        val dispatcher = Dispatchers.Default.limitedParallelism(parallelism)
        val scope = CoroutineScope(dispatcher)
        val jobs = configs.mapIndexed { idx, cfg ->
            scope.async {
                evaluateConfig(
                    cfg = cfg,
                    index = idx,
                    total = configs.size,
                    trainCandles = trainCandles,
                    valCandles = valCandles,
                    testCandles = testCandles,
                    orderBookSnapshots = orderBookSnapshots,
                    enforceOos = enforceOos,
                    verbose = verbose,
                    minTradesFloor = minTradesFloor,
                    oosMinTrades = oosMinTrades,
                    oosMinComp = oosMinComp,
                    oosMinMed = oosMinMed,
                    shortlistTopProfitPerConfig = shortlistTopProfitPerConfig,
                    shortlistTopVolumePerConfig = shortlistTopVolumePerConfig,
                    shortlistTopStablePerConfig = shortlistTopStablePerConfig,
                    useQualityGate = useQualityGate
                )
            }
        }
        jobs.awaitAll()
    }

    val configsTotal = configs.size
    val configsRun = configs.size
    val bestTrainRows = allResults.flatMap { it.bestTrainRows }
    val allCandidates = allResults.flatMap { it.candidates }
    val selectedReasons = LinkedHashMap<String, String>()

    fun writeReport(exitReason: String, finalPool: List<Candidate>, finalSelected: List<Candidate>) {
        val runDurationSec = (System.currentTimeMillis() - runStartMs) / 1000.0
        writeRunReportCsv(
            outputFile = reportCsvFile,
            runId = runId,
            runStartedAt = runStartedAt,
            runDurationSec = runDurationSec,
            exitReason = exitReason,
            symbol = symbol,
            interval = interval,
            intervalMillis = intervalMillis,
            candlesTotal = allCandles.size,
            sampleDays = sampleDays,
            useWalkForward = useWalkForward,
            split = split,
            trainCount = trainCandles.size,
            valCount = valCandles.size,
            testCount = testCandles.size,
            embargoBars = embargoBars,
            configsTotal = configsTotal,
            configsRun = configsRun,
            parallelism = parallelism,
            maxConfigsArg = maxConfigsToRun,
            logLimit = logLimit,
            minTradesFloor = minTradesFloor,
            oosMinTrades = oosMinTrades,
            oosMinComp = oosMinComp,
            oosMinMed = oosMinMed,
            requireProfitableForSelection = requireProfitableForSelection,
            targetBotCount = targetBotCount,
            quotaTopProfit = quotaTopProfit,
            quotaTopVolume = quotaTopVolume,
            quotaStable = quotaStableProfitable,
            orderBookEnabled = orderBookEnabledEffective,
            orderBookImbalanceList = orderBookImbalanceList,
            orderBookSpreadList = orderBookSpreadList,
            confluenceEnabled = confluenceEnabled,
            confluenceLookback = base.confluence.lookbackMinutes,
            confluenceMinMatches = base.confluence.minMatches,
            confluenceBuffer = base.confluence.breakoutBufferPct,
            confluenceCloseAbove = base.confluence.breakoutRequireCloseAbove,
            profitGroup = base.profitGroup,
            eventStudy = eventStudy,
            takeProfits = takeProfits,
            stopLosses = stopLosses,
            horizonsMinutes = horizonsMinutes,
            maxDrawdowns = maxDrawdowns,
            localLowLookbacks = localLowLookbacks,
            ret30mMins = ret30mMins,
            volumeZMins = volumeZMins,
            contractionMaxes = contractionMaxes,
            trendSlopeMins = trendSlopeMins,
            allResults = allResults,
            allCandidates = allCandidates,
            finalPool = finalPool,
            finalSelected = finalSelected,
            selectedReasons = selectedReasons
        )
    }

    println("\n============================================================")
    println("GLOBAL CANDIDATES: ${allCandidates.size}")
    println("============================================================")

    if (allCandidates.isEmpty()) {
        println("No candidates found. Try lowering RuleGate strictness or reducing minTradesFloor.")
        printTopTrainRows(bestTrainRows, "Top raw train configs")
        writeReport("no_candidates", emptyList(), emptyList())
        return
    }

    val finalPool = if (requireProfitableForSelection) {
        allCandidates.filter { oosScore(it) > 0.0 }
    } else {
        allCandidates
    }

    println("Final selection pool size: ${finalPool.size} (requireProfitable=$requireProfitableForSelection)")

    if (finalPool.isEmpty()) {
        println("Pool is empty after profitability filter. Set requireProfitableForSelection=false or loosen RuleGate.")
        writeReport("empty_final_pool", finalPool, emptyList())
        return
    }

    fun normalize(x: Double, lo: Double, hi: Double): Double {
        if (hi <= lo) return 0.0
        return ((x - lo) / (hi - lo)).coerceIn(0.0, 1.0)
    }

    val minComp = finalPool.minOf { oosScore(it) }
    val maxComp = finalPool.maxOf { oosScore(it) }
    val minTrades = finalPool.minOf { it.test.trades }.toDouble()
    val maxTrades = finalPool.maxOf { it.test.trades }.toDouble()

    fun balancedScore(c: Candidate): Double {
        val p = normalize(oosScore(c), minComp, maxComp)
        val t = normalize(c.test.trades.toDouble(), minTrades, maxTrades)
        return 0.65 * p + 0.35 * t
    }

    val selected = LinkedHashMap<String, Candidate>()

    fun putIfRoom(c: Candidate, reason: String) {
        if (selected.size >= targetBotCount) return
        val key = keyOf(c.cfg, c.pattern)
        if (!selected.containsKey(key)) {
            selected[key] = c
            selectedReasons[key] = reason
        }
    }

    finalPool.sortedByDescending { oosScore(it) }
        .take(quotaTopProfit)
        .forEach { putIfRoom(it, "top_profit") }

    finalPool.sortedByDescending { it.test.trades }
        .take(quotaTopVolume)
        .forEach { putIfRoom(it, "top_volume") }

    finalPool.asSequence()
        .filter { it.test.medNet > 0.0 }
        .sortedWith(compareByDescending<Candidate> { oosScore(it) }.thenByDescending { it.test.trades })
        .take(quotaStableProfitable)
        .forEach { putIfRoom(it, "stable_profitable") }

    if (selected.size < targetBotCount) {
        finalPool.asSequence()
            .sortedByDescending { balancedScore(it) }
            .filter { !selected.containsKey(keyOf(it.cfg, it.pattern)) }
            .take(targetBotCount - selected.size)
            .forEach { candidate ->
                val key = keyOf(candidate.cfg, candidate.pattern)
                selected[key] = candidate
                selectedReasons[key] = "balanced_score"
            }
    }

    val finalSelected = selected.values.take(targetBotCount)

    println("\n============================================================")
    println("SELECTED BOTS: ${finalSelected.size} / $targetBotCount")
    println("============================================================")

    val allProfitBots = finalSelected.map { c ->
        val row = c.test
        ProfitBotRow(
            cfg = c.cfg,
            pattern = c.pattern,
            tpPct = c.cfg.backtest.takeProfit * 100.0,
            trades = row.trades,
            winRate = row.winRate,
            avgNet = row.avgNet,
            medNet = row.medNet,
            sumNet = row.sumNet,
            compNet = row.compNet,
            tp = row.tp,
            sl = row.sl,
            hz = row.hz
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

    printTopTrainRows(bestTrainRows, "Top raw train configs")
    writeReport("ok", finalPool, finalSelected)

    val botSpecs = finalSelected.map { candidate ->
        BotSpec(
            name = "bot-${symbol.lowercase()}-${candidate.cfg.id()}-${candidate.pattern.replace("=", "_")}",
            patterns = candidate.patterns,
            cfg = candidate.cfg,
            trade = TradingConfig(symbol = symbol, candleInterval = interval)
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

private data class WalkForwardSplit(
    val train: Double,
    val validation: Double,
    val test: Double
)

private fun parseSplit(spec: String): WalkForwardSplit {
    val parts = spec.split(",").mapNotNull { it.trim().toDoubleOrNull() }
    if (parts.size == 3) {
        val sum = parts.sum()
        if (sum > 0.0) {
            return WalkForwardSplit(parts[0] / sum, parts[1] / sum, parts[2] / sum)
        }
    }
    return WalkForwardSplit(0.7, 0.15, 0.15)
}

private fun parseDoubleList(spec: String?): List<Double> {
    if (spec.isNullOrBlank()) return emptyList()
    return spec.split(",")
        .mapNotNull { it.trim().toDoubleOrNull() }
}

private fun minutesToBars(minutes: Int, intervalMillis: Long): Int {
    if (minutes <= 0) return 0
    val millis = minutes.toLong() * 60_000L
    return (millis / intervalMillis).toInt().coerceAtLeast(0)
}

private fun splitCandles(
    candles: List<Candle>,
    split: WalkForwardSplit,
    embargoBars: Int
): Triple<List<Candle>, List<Candle>, List<Candle>> {
    val n = candles.size
    if (n < 3) return Triple(candles, emptyList(), emptyList())

    val trainEnd = (n * split.train).toInt().coerceAtLeast(1)
    val valEnd = (n * (split.train + split.validation)).toInt().coerceAtLeast(trainEnd + 1)
        .coerceAtMost(n - 1)

    var trainEndIdx = trainEnd
    var valStartIdx = trainEnd
    var valEndIdx = valEnd
    var testStartIdx = valEnd

    if (embargoBars > 0) {
        trainEndIdx = (trainEnd - embargoBars).coerceAtLeast(1)
        valStartIdx = (trainEnd + embargoBars).coerceAtMost(valEndIdx)
        valEndIdx = (valEnd - embargoBars).coerceAtLeast(valStartIdx + 1)
        testStartIdx = (valEnd + embargoBars).coerceAtMost(n - 1)

        val invalid =
            trainEndIdx <= 0 || valStartIdx >= valEndIdx || testStartIdx >= n || trainEndIdx >= valStartIdx
        if (invalid) {
            println("Embargo too large for split; falling back to unembargoed walk-forward.")
            trainEndIdx = trainEnd
            valStartIdx = trainEnd
            valEndIdx = valEnd
            testStartIdx = valEnd
        }
    }

    val train = candles.subList(0, trainEndIdx)
    val validation = candles.subList(valStartIdx, valEndIdx)
    val test = candles.subList(testStartIdx, n)
    return Triple(train, validation, test)
}

private fun loadOrderBookSnapshots(symbol: String, sinceTime: Long): List<OrderBookSnapshot> {
    val dbPath = orderBookDbPath(symbol)
    if (!File(dbPath).exists()) return emptyList()
    val repo = OrderBookRepository.create(dbPath)
    return repo.getSnapshotsSince(symbol, sinceTime)
}

private fun computeBreakoutStats(groups: List<BreakoutGroup>, thresholds: DoubleArray): BreakoutStats? {
    if (groups.isEmpty()) return null
    val avgGain = groups.map { it.gainPctToPeakHigh }.average()
    val avgHorizon = groups.map { it.gainPctToHorizonClose }.average()
    val avgNet = groups.map { it.netPct }.average()
    val avgDd = groups.map { it.maxDrawdownPct }.average()
    val bestGain = groups.maxOf { it.gainPctToPeakHigh }
    val worstGain = groups.minOf { it.gainPctToPeakHigh }
    val thresholdCounts = thresholds
        .distinct()
        .sortedDescending()
        .associateWith { thr -> groups.count { it.thresholdHit >= thr } }
    return BreakoutStats(
        total = groups.size,
        avgGain = avgGain,
        avgHorizonGain = avgHorizon,
        avgNet = avgNet,
        avgMaxDd = avgDd,
        bestGain = bestGain,
        worstGain = worstGain,
        thresholdCounts = thresholdCounts
    )
}

private fun runSinglePattern(
    candles: List<Candle>,
    cfg: AlgoConfig,
    pattern: String,
    orderBookSnapshots: List<OrderBookSnapshot>
): SignalBacktester.PatternBacktestRow? {
    if (candles.isEmpty()) return null
    val rows = SignalBacktester.runPatternBacktests(
        candlesRaw = candles,
        patterns = listOf(pattern),
        cfg = cfg,
        useRuleGate = true,
        preferSeqOnly = false,
        orderBookSnapshots = if (cfg.orderBook.enabled) orderBookSnapshots else null,
        printReport = false
    )
    return rows.firstOrNull()
}

private fun runSingleConfluence(
    candles: List<Candle>,
    cfg: AlgoConfig,
    patterns: Set<String>,
    orderBookSnapshots: List<OrderBookSnapshot>
): SignalBacktester.PatternBacktestRow? {
    if (candles.isEmpty() || patterns.isEmpty()) return null
    return SignalBacktester.runConfluenceBreakoutBacktest(
        candlesRaw = candles,
        confluencePatterns = patterns.toList(),
        cfg = cfg,
        orderBookSnapshots = if (cfg.orderBook.enabled) orderBookSnapshots else null,
        printReport = false
    )
}

private fun keyOf(cfg: AlgoConfig, pattern: String): String = "${cfg.id()}||$pattern"

private fun oosScore(c: Candidate): Double = min(c.validation.compNet, c.test.compNet)

private fun evaluateConfig(
    cfg: AlgoConfig,
    index: Int,
    total: Int,
    trainCandles: List<Candle>,
    valCandles: List<Candle>,
    testCandles: List<Candle>,
    orderBookSnapshots: List<OrderBookSnapshot>,
    enforceOos: Boolean,
    verbose: Boolean,
    minTradesFloor: Int,
    oosMinTrades: Int,
    oosMinComp: Double,
    oosMinMed: Double,
    shortlistTopProfitPerConfig: Int,
    shortlistTopVolumePerConfig: Int,
    shortlistTopStablePerConfig: Int,
    useQualityGate: Boolean
): ConfigResult {
    val localCandidates = LinkedHashMap<String, Candidate>()
    val localBestRows = mutableListOf<SignalBacktester.PatternBacktestRow>()
    var qualityGatePassed = true
    var qualityGateReason: String? = null

    fun addLocalCandidate(candidate: Candidate) {
        val key = keyOf(candidate.cfg, candidate.pattern)
        val prev = localCandidates[key]
        if (prev == null || oosScore(candidate) > oosScore(prev)) {
            localCandidates[key] = candidate
        }
    }

    fun recordBest(row: SignalBacktester.PatternBacktestRow) {
        localBestRows += row
        localBestRows.sortByDescending { it.compNet }
        if (localBestRows.size > 10) localBestRows.removeLast()
    }

    if (verbose && index % 50 == 0) {
        println("\n============================================================")
        println("RUN #${index + 1}/$total: ${cfg.id()}")
        println("============================================================")
    }

    val finder = HistoricProfitGroupFinder(trainCandles)
    val breakoutGroups = finder.findAndReport(
        cfg = cfg,
        orderBookSnapshots = if (cfg.orderBook.enabled) orderBookSnapshots else emptyList()
    )
    val breakoutStats = computeBreakoutStats(breakoutGroups, cfg.profitGroup.thresholds)

    if (useQualityGate) {
        val gate = ProfitGroupQualityGate.decide(cfg, breakoutGroups)
        if (!gate.proceed) {
            qualityGatePassed = false
            qualityGateReason = gate.reason
            if (verbose) println("SKIP config: ${gate.reason}")
            return ConfigResult(
                cfg = cfg,
                candidates = emptyList(),
                bestTrainRows = localBestRows,
                eventStudyResult = null,
                qualityGatePassed = qualityGatePassed,
                qualityGateReason = qualityGateReason,
                breakoutStats = breakoutStats
            )
        }
    }

    val eventResult = EventStudyAnalyzer.analyzeForResult(trainCandles, breakoutGroups, cfg)
    val fullKeys = if (cfg.confluence.enabled) {
        eventResult?.topFullByLift?.map { it.key } ?: emptyList()
    } else {
        eventResult?.topFullByEdge?.map { it.key } ?: emptyList()
    }
    val eventRowsByKey = eventResult?.let { result ->
        val rows = LinkedHashMap<String, EventStudyAnalyzer.PatternRow>()
        (result.topFullByEdge + result.topFullByLift).forEach { row ->
            rows.putIfAbsent(row.key, row)
        }
        rows
    } ?: emptyMap()
    if (fullKeys.isEmpty()) {
        if (verbose) println("No patterns returned. Skipping.")
        return ConfigResult(
            cfg = cfg,
            candidates = emptyList(),
            bestTrainRows = localBestRows,
            eventStudyResult = eventResult,
            qualityGatePassed = qualityGatePassed,
            qualityGateReason = qualityGateReason,
            breakoutStats = breakoutStats
        )
    }

    if (cfg.confluence.enabled) {
        val confluencePatterns = fullKeys.toSet()
        val trainRow = SignalBacktester.runConfluenceBreakoutBacktest(
            candlesRaw = trainCandles,
            confluencePatterns = confluencePatterns.toList(),
            cfg = cfg,
            orderBookSnapshots = if (cfg.orderBook.enabled) orderBookSnapshots else null,
            printReport = verbose
        ) ?: return ConfigResult(
            cfg = cfg,
            candidates = emptyList(),
            bestTrainRows = localBestRows,
            eventStudyResult = eventResult,
            qualityGatePassed = qualityGatePassed,
            qualityGateReason = qualityGateReason,
            breakoutStats = breakoutStats
        )

        val requireStable =
            cfg.eventStudy.stabilityFolds > 1 && cfg.eventStudy.minStableFolds > 1
        val stableTrainRows = if (requireStable) {
            listOf(trainRow).filter { it.stableFolds >= cfg.eventStudy.minStableFolds }
        } else {
            listOf(trainRow)
        }

        if (stableTrainRows.isEmpty()) {
            if (verbose) println("No confluence rows passed stability filter.")
            return ConfigResult(
                cfg = cfg,
                candidates = emptyList(),
                bestTrainRows = localBestRows,
                eventStudyResult = eventResult,
                qualityGatePassed = qualityGatePassed,
                qualityGateReason = qualityGateReason,
                breakoutStats = breakoutStats
            )
        }

        stableTrainRows.forEach { recordBest(it) }
        val trainSelected = stableTrainRows.first()

        val valRow = if (enforceOos) {
            runSingleConfluence(valCandles, cfg, confluencePatterns, orderBookSnapshots)
        } else {
            trainSelected
        } ?: return ConfigResult(
            cfg = cfg,
            candidates = emptyList(),
            bestTrainRows = localBestRows,
            eventStudyResult = eventResult,
            qualityGatePassed = qualityGatePassed,
            qualityGateReason = qualityGateReason,
            breakoutStats = breakoutStats
        )

        val testRow = if (enforceOos) {
            runSingleConfluence(testCandles, cfg, confluencePatterns, orderBookSnapshots)
        } else {
            trainSelected
        } ?: return ConfigResult(
            cfg = cfg,
            candidates = emptyList(),
            bestTrainRows = localBestRows,
            eventStudyResult = eventResult,
            qualityGatePassed = qualityGatePassed,
            qualityGateReason = qualityGateReason,
            breakoutStats = breakoutStats
        )

        if (enforceOos) {
            if (valRow.trades < oosMinTrades || testRow.trades < oosMinTrades) {
                return ConfigResult(
                    cfg = cfg,
                    candidates = emptyList(),
                    bestTrainRows = localBestRows,
                    eventStudyResult = eventResult,
                    qualityGatePassed = qualityGatePassed,
                    qualityGateReason = qualityGateReason,
                    breakoutStats = breakoutStats
                )
            }
            if (valRow.compNet < oosMinComp || testRow.compNet < oosMinComp) {
                return ConfigResult(
                    cfg = cfg,
                    candidates = emptyList(),
                    bestTrainRows = localBestRows,
                    eventStudyResult = eventResult,
                    qualityGatePassed = qualityGatePassed,
                    qualityGateReason = qualityGateReason,
                    breakoutStats = breakoutStats
                )
            }
            if (valRow.medNet < oosMinMed || testRow.medNet < oosMinMed) {
                return ConfigResult(
                    cfg = cfg,
                    candidates = emptyList(),
                    bestTrainRows = localBestRows,
                    eventStudyResult = eventResult,
                    qualityGatePassed = qualityGatePassed,
                    qualityGateReason = qualityGateReason,
                    breakoutStats = breakoutStats
                )
            }
        }

        val candidate = Candidate(
            cfg = cfg,
            pattern = trainSelected.pattern,
            patterns = confluencePatterns,
            train = trainSelected,
            validation = valRow,
            test = testRow
        )

        if (candidate.test.trades < minTradesFloor) {
            return ConfigResult(
                cfg = cfg,
                candidates = emptyList(),
                bestTrainRows = localBestRows,
                eventStudyResult = eventResult,
                qualityGatePassed = qualityGatePassed,
                qualityGateReason = qualityGateReason,
                breakoutStats = breakoutStats
            )
        }

        return ConfigResult(
            cfg = cfg,
            candidates = listOf(candidate),
            bestTrainRows = localBestRows,
            eventStudyResult = eventResult,
            qualityGatePassed = qualityGatePassed,
            qualityGateReason = qualityGateReason,
            breakoutStats = breakoutStats
        )
    }

    val trainRows = SignalBacktester.runPatternBacktests(
        candlesRaw = trainCandles,
        patterns = fullKeys,
        cfg = cfg,
        useRuleGate = true,
        preferSeqOnly = false,
        orderBookSnapshots = if (cfg.orderBook.enabled) orderBookSnapshots else null,
        printReport = verbose
    )

    if (trainRows.isEmpty()) {
        if (verbose) println("Backtester returned 0 rows.")
        return ConfigResult(
            cfg = cfg,
            candidates = emptyList(),
            bestTrainRows = localBestRows,
            eventStudyResult = eventResult,
            qualityGatePassed = qualityGatePassed,
            qualityGateReason = qualityGateReason,
            breakoutStats = breakoutStats
        )
    }

    val requireStable =
        cfg.eventStudy.stabilityFolds > 1 && cfg.eventStudy.minStableFolds > 1
    val stableTrainRows = if (requireStable) {
        trainRows.filter { it.stableFolds >= cfg.eventStudy.minStableFolds }
    } else {
        trainRows
    }

    if (requireStable && stableTrainRows.isEmpty()) {
        if (verbose) println("No patterns passed stability filter.")
        return ConfigResult(
            cfg = cfg,
            candidates = emptyList(),
            bestTrainRows = localBestRows,
            eventStudyResult = eventResult,
            qualityGatePassed = qualityGatePassed,
            qualityGateReason = qualityGateReason,
            breakoutStats = breakoutStats
        )
    }

    stableTrainRows.forEach { recordBest(it) }

    val candidates = stableTrainRows
        .asSequence()
        .filter { it.trades >= minTradesFloor }
        .mapNotNull { r ->
            val valRow = if (enforceOos) {
                runSinglePattern(valCandles, cfg, r.pattern, orderBookSnapshots)
            } else {
                r
            } ?: return@mapNotNull null

            val testRow = if (enforceOos) {
                runSinglePattern(testCandles, cfg, r.pattern, orderBookSnapshots)
            } else {
                r
            } ?: return@mapNotNull null

            if (enforceOos) {
                if (valRow.trades < oosMinTrades || testRow.trades < oosMinTrades) return@mapNotNull null
                if (valRow.compNet < oosMinComp || testRow.compNet < oosMinComp) return@mapNotNull null
                if (valRow.medNet < oosMinMed || testRow.medNet < oosMinMed) return@mapNotNull null
            }

            Candidate(
                cfg = cfg,
                pattern = r.pattern,
                train = r,
                validation = valRow,
                test = testRow,
                eventStudy = eventRowsByKey[r.pattern]
            )
        }
        .toList()

    if (candidates.isEmpty()) {
        if (verbose) println("No candidates after OOS filters.")
        return ConfigResult(
            cfg = cfg,
            candidates = emptyList(),
            bestTrainRows = localBestRows,
            eventStudyResult = eventResult,
            qualityGatePassed = qualityGatePassed,
            qualityGateReason = qualityGateReason,
            breakoutStats = breakoutStats
        )
    }

    val topProfit = candidates
        .sortedByDescending { oosScore(it) }
        .take(shortlistTopProfitPerConfig)

    val topVolumeProfitable = candidates
        .sortedByDescending { it.test.trades }
        .take(shortlistTopVolumePerConfig)

    val topStableProfitable = candidates
        .filter { it.test.medNet > 0.0 }
        .sortedWith(compareByDescending<Candidate> { oosScore(it) }.thenByDescending { it.test.trades })
        .take(shortlistTopStablePerConfig)

    (topProfit + topVolumeProfitable + topStableProfitable).forEach { addLocalCandidate(it) }

    if (verbose) {
        println(
            "Shortlisted this config: " +
                    "profit=${topProfit.size}, " +
                    "volume=${topVolumeProfitable.size}, " +
                    "stable=${topStableProfitable.size} " +
                    " | localUnique=${localCandidates.size}"
        )
    }

    return ConfigResult(
        cfg = cfg,
        candidates = localCandidates.values.toList(),
        bestTrainRows = localBestRows,
        eventStudyResult = eventResult,
        qualityGatePassed = qualityGatePassed,
        qualityGateReason = qualityGateReason,
        breakoutStats = breakoutStats
    )
}

private fun printTopTrainRows(rows: List<SignalBacktester.PatternBacktestRow>, title: String) {
    if (rows.isEmpty()) return
    println("\n$title (${rows.size} tracked rows)")
    rows.sortedByDescending { it.compNet }.take(5).forEachIndexed { idx, r ->
        val stableLabel = if (r.foldCount <= 1) "-" else "${r.stableFolds}/${r.foldCount}"
        println(
            "${(idx + 1).toString().padStart(2)} " +
                    "pattern=${r.pattern} compNet=${"%.4f".format(r.compNet)} " +
                    "medNet=${"%.4f".format(r.medNet)} trades=${r.trades} " +
                    "stable=$stableLabel minFoldNet=${"%.4f".format(r.minFoldAvgNet)}"
        )
    }
}

private data class StatSummary(
    val min: Double,
    val median: Double,
    val mean: Double,
    val max: Double
)

private fun writeRunReportCsv(
    outputFile: File,
    runId: String,
    runStartedAt: Instant,
    runDurationSec: Double,
    exitReason: String,
    symbol: String,
    interval: String,
    intervalMillis: Long,
    candlesTotal: Int,
    sampleDays: Double,
    useWalkForward: Boolean,
    split: WalkForwardSplit,
    trainCount: Int,
    valCount: Int,
    testCount: Int,
    embargoBars: Int,
    configsTotal: Int,
    configsRun: Int,
    parallelism: Int,
    maxConfigsArg: Int?,
    logLimit: Int,
    minTradesFloor: Int,
    oosMinTrades: Int,
    oosMinComp: Double,
    oosMinMed: Double,
    requireProfitableForSelection: Boolean,
    targetBotCount: Int,
    quotaTopProfit: Int,
    quotaTopVolume: Int,
    quotaStable: Int,
    orderBookEnabled: Boolean,
    orderBookImbalanceList: List<Double>,
    orderBookSpreadList: List<Double>,
    confluenceEnabled: Boolean,
    confluenceLookback: Int,
    confluenceMinMatches: Int,
    confluenceBuffer: Double,
    confluenceCloseAbove: Boolean,
    profitGroup: com.example.platformutil.ProfitGroupConfig,
    eventStudy: com.example.platformutil.EventStudyConfig,
    takeProfits: List<Double>,
    stopLosses: List<Double>,
    horizonsMinutes: List<Int>,
    maxDrawdowns: List<Double>,
    localLowLookbacks: List<Int>,
    ret30mMins: List<Double>,
    volumeZMins: List<Double>,
    contractionMaxes: List<Double>,
    trendSlopeMins: List<Double>,
    allResults: List<ConfigResult>,
    allCandidates: List<Candidate>,
    finalPool: List<Candidate>,
    finalSelected: List<Candidate>,
    selectedReasons: Map<String, String>
) {
    outputFile.parentFile?.mkdirs()
    PrintWriter(outputFile).use { writer ->
        val headers = listOf(
            "section",
            "run_id",
            "run_started_at",
            "run_duration_sec",
            "symbol",
            "interval",
            "interval_ms",
            "candles_total",
            "sample_days",
            "walk_forward",
            "split_train",
            "split_val",
            "split_test",
            "train_candles",
            "val_candles",
            "test_candles",
            "embargo_bars",
            "configs_total",
            "configs_run",
            "parallelism",
            "max_configs_arg",
            "log_limit_lines",
            "min_trades_floor",
            "oos_min_trades",
            "oos_min_comp_pct",
            "oos_min_med_pct",
            "require_profitable",
            "target_bot_count",
            "quota_top_profit",
            "quota_top_volume",
            "quota_stable",
            "orderbook_enabled",
            "orderbook_min_imb10",
            "orderbook_max_spread_bps",
            "confluence_enabled",
            "confluence_lookback_min",
            "confluence_min_matches",
            "confluence_buffer_pct",
            "confluence_close_above",
            "profit_mode",
            "profit_thresholds_pct",
            "profit_local_low_lookback_min",
            "profit_max_drawdown_pct",
            "profit_require_continuous",
            "profit_dedupe_overlapping",
            "profit_sort_by",
            "event_topk",
            "event_min_pos",
            "event_pattern_bars",
            "event_context_bars",
            "event_neg_every",
            "event_min_net_pct",
            "event_embargo_min",
            "event_stability_folds",
            "event_min_stable_folds",
            "event_min_pos_per_fold",
            "event_max_fdr",
            "event_regime_min_buckets",
            "event_regime_min_pos",
            "event_min_pos_events",
            "event_min_neg_samples",
            "event_min_distinct_full",
            "metric",
            "metric_value",
            "cfg_id",
            "tp_pct",
            "sl_pct",
            "horizon_min",
            "max_drawdown_pct",
            "profit_thresholds_pct_cfg",
            "profit_require_continuous_cfg",
            "profit_dedupe_overlapping_cfg",
            "local_low_lookback_min",
            "ret30m_min_pct",
            "volume_z_min",
            "contraction_max",
            "trend_slope_min",
            "orderbook_cfg_enabled",
            "orderbook_min_imb10_cfg",
            "orderbook_max_spread_bps_cfg",
            "confluence_cfg_enabled",
            "confluence_lookback_min_cfg",
            "confluence_min_matches_cfg",
            "confluence_buffer_pct_cfg",
            "confluence_close_above_cfg",
            "pattern",
            "patterns",
            "patterns_count",
            "is_confluence",
            "selection_in_pool",
            "selection_selected",
            "selection_reason",
            "oos_score_pct",
            "train_signals",
            "train_trades",
            "train_trades_per_day",
            "train_win_rate_pct",
            "train_avg_net_pct",
            "train_med_net_pct",
            "train_sum_net_pct",
            "train_comp_net_pct",
            "train_tp",
            "train_sl",
            "train_hz",
            "train_stable_folds",
            "train_fold_count",
            "train_min_fold_avg_net_pct",
            "val_signals",
            "val_trades",
            "val_trades_per_day",
            "val_win_rate_pct",
            "val_avg_net_pct",
            "val_med_net_pct",
            "val_sum_net_pct",
            "val_comp_net_pct",
            "val_tp",
            "val_sl",
            "val_hz",
            "val_stable_folds",
            "val_fold_count",
            "val_min_fold_avg_net_pct",
            "test_signals",
            "test_trades",
            "test_trades_per_day",
            "test_win_rate_pct",
            "test_avg_net_pct",
            "test_med_net_pct",
            "test_sum_net_pct",
            "test_comp_net_pct",
            "test_tp",
            "test_sl",
            "test_hz",
            "test_stable_folds",
            "test_fold_count",
            "test_min_fold_avg_net_pct",
            "event_pos",
            "event_neg",
            "event_pos_rate",
            "event_neg_rate",
            "event_lift",
            "event_p_value",
            "event_regime_buckets",
            "event_avg_net_pct",
            "event_avg_peak_pct",
            "event_avg_horizon_pct",
            "event_avg_max_dd_pct",
            "event_hit5",
            "event_hit3",
            "event_hit2",
            "event_rank_type",
            "event_rank_index",
            "config_candidates",
            "config_quality_gate",
            "config_quality_gate_reason",
            "config_breakout_groups",
            "config_breakout_avg_gain_pct",
            "config_breakout_avg_net_pct",
            "config_breakout_avg_dd_pct",
            "config_breakout_avg_horizon_pct",
            "config_breakout_best_gain_pct",
            "config_breakout_worst_gain_pct",
            "config_breakout_threshold_counts",
            "config_event_pos_total",
            "config_event_neg_total",
            "config_event_pos_used",
            "config_event_neg_used"
        )

        writer.println(headers.joinToString(","))

        val base = linkedMapOf(
            "run_id" to runId,
            "run_started_at" to runStartedAt.toString(),
            "run_duration_sec" to fmtDouble(runDurationSec, 2),
            "symbol" to symbol,
            "interval" to interval,
            "interval_ms" to intervalMillis.toString(),
            "candles_total" to candlesTotal.toString(),
            "sample_days" to fmtDouble(sampleDays, 2),
            "walk_forward" to useWalkForward.toString(),
            "split_train" to fmtDouble(split.train * 100.0, 2),
            "split_val" to fmtDouble(split.validation * 100.0, 2),
            "split_test" to fmtDouble(split.test * 100.0, 2),
            "train_candles" to trainCount.toString(),
            "val_candles" to valCount.toString(),
            "test_candles" to testCount.toString(),
            "embargo_bars" to embargoBars.toString(),
            "configs_total" to configsTotal.toString(),
            "configs_run" to configsRun.toString(),
            "parallelism" to parallelism.toString(),
            "max_configs_arg" to (maxConfigsArg?.toString() ?: ""),
            "log_limit_lines" to logLimit.toString(),
            "min_trades_floor" to minTradesFloor.toString(),
            "oos_min_trades" to oosMinTrades.toString(),
            "oos_min_comp_pct" to fmtPct(oosMinComp),
            "oos_min_med_pct" to fmtPct(oosMinMed),
            "require_profitable" to requireProfitableForSelection.toString(),
            "target_bot_count" to targetBotCount.toString(),
            "quota_top_profit" to quotaTopProfit.toString(),
            "quota_top_volume" to quotaTopVolume.toString(),
            "quota_stable" to quotaStable.toString(),
            "orderbook_enabled" to orderBookEnabled.toString(),
            "orderbook_min_imb10" to joinDoubles(orderBookImbalanceList),
            "orderbook_max_spread_bps" to joinDoubles(orderBookSpreadList),
            "confluence_enabled" to confluenceEnabled.toString(),
            "confluence_lookback_min" to confluenceLookback.toString(),
            "confluence_min_matches" to confluenceMinMatches.toString(),
            "confluence_buffer_pct" to fmtPct(confluenceBuffer),
            "confluence_close_above" to confluenceCloseAbove.toString(),
            "profit_mode" to profitGroup.mode.toString(),
            "profit_thresholds_pct" to profitGroup.thresholds.joinToString("|") { fmtPct(it) },
            "profit_local_low_lookback_min" to profitGroup.localLowLookbackMinutes.toString(),
            "profit_max_drawdown_pct" to fmtPct(profitGroup.maxDrawdownAllowed),
            "profit_require_continuous" to profitGroup.requireContinuous.toString(),
            "profit_dedupe_overlapping" to profitGroup.dedupeOverlapping.toString(),
            "profit_sort_by" to profitGroup.sortBy.toString(),
            "event_topk" to eventStudy.topK.toString(),
            "event_min_pos" to eventStudy.minPosCount.toString(),
            "event_pattern_bars" to eventStudy.patternBars.toString(),
            "event_context_bars" to eventStudy.contextBars.toString(),
            "event_neg_every" to eventStudy.negativeSampleEveryN.toString(),
            "event_min_net_pct" to fmtPct(eventStudy.minNetEdge),
            "event_embargo_min" to eventStudy.embargoMinutes.toString(),
            "event_stability_folds" to eventStudy.stabilityFolds.toString(),
            "event_min_stable_folds" to eventStudy.minStableFolds.toString(),
            "event_min_pos_per_fold" to eventStudy.minPosPerFold.toString(),
            "event_max_fdr" to fmtDouble(eventStudy.maxFdr, 4),
            "event_regime_min_buckets" to eventStudy.regimeMinBuckets.toString(),
            "event_regime_min_pos" to eventStudy.regimeMinPosPerBucket.toString(),
            "event_min_pos_events" to eventStudy.minPosEventsToRun.toString(),
            "event_min_neg_samples" to eventStudy.minNegSamplesToRun.toString(),
            "event_min_distinct_full" to eventStudy.minDistinctFullKeysToRun.toString()
        )

        val summaryRows = mutableListOf<Pair<String, String>>()
        summaryRows += "report_csv_path" to outputFile.absolutePath
        summaryRows += "exit_reason" to exitReason
        summaryRows += "candidates_total" to allCandidates.size.toString()
        summaryRows += "final_pool_total" to finalPool.size.toString()
        summaryRows += "selected_total" to finalSelected.size.toString()
        summaryRows += "configs_with_candidates" to allResults.count { it.candidates.isNotEmpty() }.toString()
        summaryRows += "configs_quality_gate_passed" to allResults.count { it.qualityGatePassed }.toString()
        summaryRows += "configs_quality_gate_failed" to allResults.count { !it.qualityGatePassed }.toString()
        summaryRows += "selected_top_profit" to selectedReasons.values.count { it == "top_profit" }.toString()
        summaryRows += "selected_top_volume" to selectedReasons.values.count { it == "top_volume" }.toString()
        summaryRows += "selected_stable_profitable" to selectedReasons.values.count { it == "stable_profitable" }.toString()
        summaryRows += "selected_balanced_score" to selectedReasons.values.count { it == "balanced_score" }.toString()
        summaryRows += "grid_take_profit_pct" to takeProfits.joinToString("|") { fmtPct(it) }
        summaryRows += "grid_stop_loss_pct" to stopLosses.joinToString("|") { fmtPct(it) }
        summaryRows += "grid_horizon_min" to horizonsMinutes.joinToString("|")
        summaryRows += "grid_max_drawdown_pct" to maxDrawdowns.joinToString("|") { fmtPct(it) }
        summaryRows += "grid_local_low_lookback_min" to localLowLookbacks.joinToString("|")
        summaryRows += "grid_ret30m_min_pct" to ret30mMins.joinToString("|") { fmtPct(it) }
        summaryRows += "grid_volume_z_min" to volumeZMins.joinToString("|") { fmtDouble(it, 2) }
        summaryRows += "grid_contraction_max" to contractionMaxes.joinToString("|") { fmtDouble(it, 2) }
        summaryRows += "grid_trend_slope_min" to trendSlopeMins.joinToString("|") { fmtDouble(it, 2) }

        addStatMetrics(summaryRows, "oos_score_pct", allCandidates.map { oosScore(it) }, ::fmtPct)
        addStatMetrics(summaryRows, "test_comp_net_pct", allCandidates.map { it.test.compNet }, ::fmtPct)
        addStatMetrics(summaryRows, "test_win_rate_pct", allCandidates.map { it.test.winRate }, ::fmtPct)
        addStatMetrics(summaryRows, "test_trades", allCandidates.map { it.test.trades.toDouble() }, ::fmtDouble)
        addStatMetrics(summaryRows, "test_trades_per_day", allCandidates.map { it.test.tradesPerDay }, ::fmtDouble)
        addStatMetrics(summaryRows, "selected_comp_net_pct", finalSelected.map { it.test.compNet }, ::fmtPct)
        addStatMetrics(summaryRows, "selected_win_rate_pct", finalSelected.map { it.test.winRate }, ::fmtPct)
        addStatMetrics(summaryRows, "selected_trades", finalSelected.map { it.test.trades.toDouble() }, ::fmtDouble)

        summaryRows.forEach { (metric, value) ->
            writeCsvRow(
                writer,
                headers,
                base,
                mapOf(
                    "section" to "run_summary",
                    "metric" to metric,
                    "metric_value" to value
                )
            )
        }

        allResults.forEach { result ->
            val overrides = LinkedHashMap(configColumns(result.cfg))
            overrides["section"] = "config_summary"
            overrides["config_candidates"] = result.candidates.size.toString()
            overrides["config_quality_gate"] = result.qualityGatePassed.toString()
            overrides["config_quality_gate_reason"] = result.qualityGateReason ?: ""
            result.breakoutStats?.let { stats ->
                overrides["config_breakout_groups"] = stats.total.toString()
                overrides["config_breakout_avg_gain_pct"] = fmtPct(stats.avgGain)
                overrides["config_breakout_avg_net_pct"] = fmtPct(stats.avgNet)
                overrides["config_breakout_avg_dd_pct"] = fmtPct(stats.avgMaxDd)
                overrides["config_breakout_avg_horizon_pct"] = fmtPct(stats.avgHorizonGain)
                overrides["config_breakout_best_gain_pct"] = fmtPct(stats.bestGain)
                overrides["config_breakout_worst_gain_pct"] = fmtPct(stats.worstGain)
                overrides["config_breakout_threshold_counts"] = stats.thresholdCounts
                    .entries
                    .joinToString("|") { "${fmtPct(it.key)}=${it.value}" }
            }
            result.eventStudyResult?.let { ev ->
                overrides["config_event_pos_total"] = ev.posGroupsTotal.toString()
                overrides["config_event_neg_total"] = ev.negSampleTotal.toString()
                overrides["config_event_pos_used"] = ev.posUsed.toString()
                overrides["config_event_neg_used"] = ev.negUsed.toString()
            }
            writeCsvRow(writer, headers, base, overrides)
        }

        allResults.forEach { result ->
            val ev = result.eventStudyResult ?: return@forEach
            val cfgColumns = configColumns(result.cfg)
            fun writeEventRows(rows: List<EventStudyAnalyzer.PatternRow>, rankType: String) {
                rows.forEachIndexed { idx, row ->
                    val overrides = LinkedHashMap(cfgColumns)
                    overrides["section"] = "event_study_pattern"
                    overrides["pattern"] = row.key
                    overrides["event_rank_type"] = rankType
                    overrides["event_rank_index"] = (idx + 1).toString()
                    overrides.putAll(eventMetrics(row))
                    writeCsvRow(writer, headers, base, overrides)
                }
            }
            writeEventRows(ev.topFullByLift, "full_lift")
            writeEventRows(ev.topFullByEdge, "full_edge")
            writeEventRows(ev.topCollapsedByLift, "collapsed_lift")
            writeEventRows(ev.topCollapsedByPos, "collapsed_pos")
        }

        val finalPoolKeys = finalPool.map { keyOf(it.cfg, it.pattern) }.toSet()
        allCandidates.forEach { candidate ->
            val overrides = LinkedHashMap(configColumns(candidate.cfg))
            val key = keyOf(candidate.cfg, candidate.pattern)
            overrides["section"] = "candidate"
            overrides["pattern"] = candidate.pattern
            overrides["patterns"] = candidate.patterns.joinToString(";")
            overrides["patterns_count"] = candidate.patterns.size.toString()
            overrides["is_confluence"] = candidate.cfg.confluence.enabled.toString()
            overrides["selection_in_pool"] = finalPoolKeys.contains(key).toString()
            overrides["selection_selected"] = selectedReasons.containsKey(key).toString()
            overrides["selection_reason"] = selectedReasons[key] ?: ""
            overrides["oos_score_pct"] = fmtPct(oosScore(candidate))
            overrides.putAll(patternMetrics("train_", candidate.train))
            overrides.putAll(patternMetrics("val_", candidate.validation))
            overrides.putAll(patternMetrics("test_", candidate.test))
            candidate.eventStudy?.let { overrides.putAll(eventMetrics(it)) }
            writeCsvRow(writer, headers, base, overrides)
        }
    }
}

private fun configColumns(cfg: AlgoConfig): Map<String, String> = linkedMapOf(
    "cfg_id" to cfg.id(),
    "tp_pct" to fmtPct(cfg.backtest.takeProfit),
    "sl_pct" to fmtPct(cfg.backtest.stopLoss),
    "horizon_min" to cfg.backtest.horizonMinutes.toString(),
    "max_drawdown_pct" to fmtPct(cfg.profitGroup.maxDrawdownAllowed),
    "profit_thresholds_pct_cfg" to cfg.profitGroup.thresholds.joinToString("|") { fmtPct(it) },
    "profit_require_continuous_cfg" to cfg.profitGroup.requireContinuous.toString(),
    "profit_dedupe_overlapping_cfg" to cfg.profitGroup.dedupeOverlapping.toString(),
    "local_low_lookback_min" to cfg.profitGroup.localLowLookbackMinutes.toString(),
    "ret30m_min_pct" to fmtPct(cfg.signal.ret30mMin),
    "volume_z_min" to fmtDouble(cfg.signal.volumeZMin, 4),
    "contraction_max" to fmtDouble(cfg.signal.contractionMax, 4),
    "trend_slope_min" to fmtDouble(cfg.signal.trendSlopeMin, 4),
    "orderbook_cfg_enabled" to cfg.orderBook.enabled.toString(),
    "orderbook_min_imb10_cfg" to fmtDouble(cfg.orderBook.minImbalance10, 4),
    "orderbook_max_spread_bps_cfg" to fmtDouble(cfg.orderBook.maxSpreadBps, 4),
    "confluence_cfg_enabled" to cfg.confluence.enabled.toString(),
    "confluence_lookback_min_cfg" to cfg.confluence.lookbackMinutes.toString(),
    "confluence_min_matches_cfg" to cfg.confluence.minMatches.toString(),
    "confluence_buffer_pct_cfg" to fmtPct(cfg.confluence.breakoutBufferPct),
    "confluence_close_above_cfg" to cfg.confluence.breakoutRequireCloseAbove.toString()
)

private fun patternMetrics(prefix: String, row: SignalBacktester.PatternBacktestRow): Map<String, String> = linkedMapOf(
    "${prefix}signals" to row.signals.toString(),
    "${prefix}trades" to row.trades.toString(),
    "${prefix}trades_per_day" to fmtDouble(row.tradesPerDay, 6),
    "${prefix}win_rate_pct" to fmtPct(row.winRate),
    "${prefix}avg_net_pct" to fmtPct(row.avgNet),
    "${prefix}med_net_pct" to fmtPct(row.medNet),
    "${prefix}sum_net_pct" to fmtPct(row.sumNet),
    "${prefix}comp_net_pct" to fmtPct(row.compNet),
    "${prefix}tp" to row.tp.toString(),
    "${prefix}sl" to row.sl.toString(),
    "${prefix}hz" to row.hz.toString(),
    "${prefix}stable_folds" to row.stableFolds.toString(),
    "${prefix}fold_count" to row.foldCount.toString(),
    "${prefix}min_fold_avg_net_pct" to fmtPct(row.minFoldAvgNet)
)

private fun eventMetrics(row: EventStudyAnalyzer.PatternRow): Map<String, String> = linkedMapOf(
    "event_pos" to row.posCount.toString(),
    "event_neg" to row.negCount.toString(),
    "event_pos_rate" to fmtDouble(row.posRate, 6),
    "event_neg_rate" to fmtDouble(row.negRate, 6),
    "event_lift" to fmtDouble(row.lift, 6),
    "event_p_value" to fmtDouble(row.pValue, 6),
    "event_regime_buckets" to row.regimeBuckets.toString(),
    "event_avg_net_pct" to fmtPct(row.avgNet),
    "event_avg_peak_pct" to fmtPct(row.avgPeakGain),
    "event_avg_horizon_pct" to fmtPct(row.avgHorizonGain),
    "event_avg_max_dd_pct" to fmtPct(row.avgMaxDd),
    "event_hit5" to row.hit5.toString(),
    "event_hit3" to row.hit3.toString(),
    "event_hit2" to row.hit2.toString()
)

private fun addStatMetrics(
    rows: MutableList<Pair<String, String>>,
    name: String,
    values: List<Double>,
    formatter: (Double) -> String
) {
    val summary = statSummary(values) ?: return
    rows += "${name}_min" to formatter(summary.min)
    rows += "${name}_median" to formatter(summary.median)
    rows += "${name}_mean" to formatter(summary.mean)
    rows += "${name}_max" to formatter(summary.max)
}

private fun statSummary(values: List<Double>): StatSummary? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    val mid = sorted.size / 2
    val median = if (sorted.size % 2 == 0) {
        (sorted[mid - 1] + sorted[mid]) / 2.0
    } else {
        sorted[mid]
    }
    return StatSummary(
        min = sorted.first(),
        median = median,
        mean = sorted.average(),
        max = sorted.last()
    )
}

private fun writeCsvRow(
    writer: PrintWriter,
    headers: List<String>,
    base: Map<String, String>,
    overrides: Map<String, String>
) {
    val line = headers.joinToString(",") { header ->
        csvEscape(overrides[header] ?: base[header] ?: "")
    }
    writer.println(line)
}

private fun csvEscape(value: String): String {
    val needsQuote = value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')
    if (!needsQuote) return value
    val escaped = value.replace("\"", "\"\"")
    return "\"$escaped\""
}

private fun joinDoubles(values: List<Double>): String =
    values.joinToString("|") { fmtDouble(it, 4) }

private fun fmtDouble(value: Double, decimals: Int = 6): String =
    if (value.isNaN()) "" else String.format(Locale.US, "%.${decimals}f", value)

private fun fmtPct(value: Double): String = fmtDouble(value * 100.0, 4)

private fun installLogLimiter(maxLines: Int) {
    if (maxLines <= 0) return
    val originalOut = System.out
    val limiter = LineLimitOutputStream(originalOut, maxLines) {
        originalOut.println("Log limit reached (${maxLines} lines); further output suppressed.")
    }
    System.setOut(PrintStream(limiter, true))
}

private class LineLimitOutputStream(
    private val delegate: OutputStream,
    private val maxLines: Int,
    private val onLimitReached: () -> Unit
) : OutputStream() {
    private var lineCount = 0
    private var limited = false
    private var notified = false

    override fun write(b: Int) {
        if (limited) return
        delegate.write(b)
        if (b == '\n'.code) {
            lineCount++
            if (lineCount >= maxLines) {
                limit()
            }
        }
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (limited) return
        val end = off + len
        var i = off
        while (i < end && !limited) {
            val byteVal = b[i].toInt()
            delegate.write(byteVal)
            if (byteVal == '\n'.code) {
                lineCount++
                if (lineCount >= maxLines) {
                    limit()
                }
            }
            i++
        }
    }

    private fun limit() {
        if (notified) return
        notified = true
        limited = true
        onLimitReached()
    }
}
