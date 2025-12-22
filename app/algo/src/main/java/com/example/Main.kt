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
import kotlin.math.min
import kotlinx.coroutines.*
import kotlinx.coroutines.ExperimentalCoroutinesApi

private data class Candidate(
    val cfg: AlgoConfig,
    val pattern: String,
    val train: SignalBacktester.PatternBacktestRow,
    val validation: SignalBacktester.PatternBacktestRow,
    val test: SignalBacktester.PatternBacktestRow
)

private data class ConfigResult(
    val candidates: List<Candidate>,
    val bestTrainRows: List<SignalBacktester.PatternBacktestRow>
)

@OptIn(ExperimentalCoroutinesApi::class)
fun main(args: Array<String>) {
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
        orderBook = AlgoConfig().orderBook.copy(enabled = orderBookEnabledEffective)
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

    val configs = AlgoConfigSweeps.grid(
        base = base,

        // Backtest knobs
        takeProfits = listOf(0.005, 0.008, 0.012, 0.016),
        stopLosses = listOf(0.003, 0.004, 0.006, 0.008),
        horizonsMinutes = listOf(30, 60, 90),
        maxDrawdowns = listOf(0.05, 0.10),
        localLowLookbacks = listOf(0, 2, 3, 5),

        // RuleGate knobs (affects frequency heavily)
        ret30mMins = listOf(-0.06, -0.05, -0.04, -0.03, -0.02, -0.01, -0.005),
        volumeZMins = listOf(-2.0, -1.5, -1.0, -0.5, 0.0),
        contractionMaxes = listOf(1.05, 1.10, 1.20),
        trendSlopeMins = listOf(0.0),
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

    val bestTrainRows = allResults.flatMap { it.bestTrainRows }
    val allCandidates = allResults.flatMap { it.candidates }
    println("\n============================================================")
    println("GLOBAL CANDIDATES: ${allCandidates.size}")
    println("============================================================")

    if (allCandidates.isEmpty()) {
        println("No candidates found. Try lowering RuleGate strictness or reducing minTradesFloor.")
        printTopTrainRows(bestTrainRows, "Top raw train configs")
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

    // Quotas (remainder filled by balanced score)
    val quotaTopProfit = 20
    val quotaTopVolume = 15
    val quotaStableProfitable = 10

    val selected = LinkedHashMap<String, Candidate>()

    fun putIfRoom(c: Candidate) {
        if (selected.size >= targetBotCount) return
        selected.putIfAbsent(keyOf(c.cfg, c.pattern), c)
    }

    finalPool.sortedByDescending { oosScore(it) }
        .take(quotaTopProfit)
        .forEach(::putIfRoom)

    finalPool.sortedByDescending { it.test.trades }
        .take(quotaTopVolume)
        .forEach(::putIfRoom)

    finalPool.asSequence()
        .filter { it.test.medNet > 0.0 }
        .sortedWith(compareByDescending<Candidate> { oosScore(it) }.thenByDescending { it.test.trades })
        .take(quotaStableProfitable)
        .forEach(::putIfRoom)

    if (selected.size < targetBotCount) {
        finalPool.asSequence()
            .sortedByDescending { balancedScore(it) }
            .filter { !selected.containsKey(keyOf(it.cfg, it.pattern)) }
            .take(targetBotCount - selected.size)
            .forEach { candidate ->
                selected[keyOf(candidate.cfg, candidate.pattern)] = candidate
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

    val botSpecs = allProfitBots.map { row ->
        BotSpec(
            name = "bot-${symbol.lowercase()}-${row.cfg.id()}-${row.pattern.replace("=", "_")}",
            patterns = setOf(row.pattern),
            cfg = row.cfg,
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

    if (useQualityGate) {
        val gate = ProfitGroupQualityGate.decide(cfg, breakoutGroups)
        if (!gate.proceed) {
            if (verbose) println("SKIP config: ${gate.reason}")
            return ConfigResult(emptyList(), localBestRows)
        }
    }

    val fullKeys =
        EventStudyAnalyzer.runAndGetTopFullKeysByEdge(trainCandles, breakoutGroups, cfg)
    if (fullKeys.isEmpty()) {
        if (verbose) println("No patterns returned. Skipping.")
        return ConfigResult(emptyList(), localBestRows)
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
        return ConfigResult(emptyList(), localBestRows)
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
        return ConfigResult(emptyList(), localBestRows)
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
                test = testRow
            )
        }
        .toList()

    if (candidates.isEmpty()) {
        if (verbose) println("No candidates after OOS filters.")
        return ConfigResult(emptyList(), localBestRows)
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

    return ConfigResult(localCandidates.values.toList(), localBestRows)
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
