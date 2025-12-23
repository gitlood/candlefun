package com.example.stackeddca

import com.example.historicaldata.HistoricalDataRepository
import com.example.historicaldata.runHistoricalDataUpdate
import com.example.platformutil.CandleJob
import com.example.platformutil.candleDbPath
import com.example.platformutil.intervalToMillis
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.abs
import com.example.liveklines.streamClosed1mCandles
import com.example.network.BinanceUniverse
import com.example.network.SymbolLiquidity

fun main(args: Array<String>) {
    val config = parseArgs(args)
    val universe = BinanceUniverse()

    when (config.mode) {
        Mode.DISCOVER -> {
            val symbols = runBlocking { universe.fetchTopSymbols(config.universe) }
            printUniverse(symbols)
        }
        Mode.INGEST -> {
            val symbols = resolveSymbols(config, universe)
            ingestSymbols(symbols, config.ingest)
        }
        Mode.BACKTEST -> {
            val symbols = resolveSymbols(config, universe)
            runBacktests(symbols, config.ingest, config.backtest)
        }
        Mode.SWEEP -> {
            val symbols = resolveSymbols(config, universe)
            runSweep(symbols, config.ingest, config.backtest, config.sweep)
        }
        Mode.ALL -> {
            val symbols = resolveSymbols(config, universe)
            runPerSymbolPipeline(symbols, config.ingest, config.backtest)
        }
        Mode.LIVE -> {
            val symbols = resolveSymbols(config, universe)
            runLivePaper(symbols, config.ingest, config.backtest)
        }

    }
}

private fun runLivePaper(symbols: List<String>, ingest: IngestConfig, backtest: BacktestConfig) = runBlocking {
    if (symbols.isEmpty()) {
        println("No symbols for LIVE.")
        return@runBlocking
    }

    val engines = symbols.associateWith { _ ->
        StackedDcaEngine(cfg = backtest, interval = ingest.interval)
    }

    println("LIVE (paper) streaming ${symbols.size} symbols: ${symbols.joinToString(",")}")

    streamClosed1mCandles(symbols) { sym, candle ->
        val engine = engines[sym] ?: return@streamClosed1mCandles
        engine.onCandle(sym, candle)

        // super simple heartbeat:
        if (candle.openTime % (60_000L * 30) == 0L) { // approx every 30 mins
            println("${sym} equity=${"%.2f".format(engine.equity(candle.close))} lastClose=${candle.close}")
        }
    }
}


private fun resolveSymbols(config: AppConfig, universe: BinanceUniverse): List<String> {
    config.symbols?.let { return it }
    val symbols = runBlocking { universe.fetchTopSymbols(config.universe) }
    return symbols.map { it.symbol }
}

private fun ingestSymbols(symbols: List<String>, ingest: IngestConfig) {
    if (symbols.isEmpty()) {
        println("No symbols to ingest.")
        return
    }

    val dbDir = File(ingest.dbDir)
    if (!dbDir.exists()) {
        dbDir.mkdirs()
    }

    val jobs = symbols.map { symbol ->
        CandleJob(
            symbol = symbol,
            interval = ingest.interval,
            dbPath = stackedCandleDbPath(ingest.dbDir, symbol, ingest.interval)
        )
    }

    runHistoricalDataUpdate(jobs)
}

private fun runBacktests(
    symbols: List<String>,
    ingest: IngestConfig,
    backtest: BacktestConfig,
    shouldPrint: Boolean = true,
    csvSuffix: String? = null
): List<BacktestResult> {
    if (symbols.isEmpty()) {
        println("No symbols to backtest.")
        return emptyList()
    }

    val exportDir = File(ingest.exportDir)
    if (!exportDir.exists()) {
        exportDir.mkdirs()
    }

    val results = mutableListOf<BacktestResult>()
    for (symbol in symbols) {
        runBacktestForSymbol(symbol, ingest, backtest, exportDir, csvSuffix)?.let { results.add(it) }
    }

    if (shouldPrint) {
        printResults(results)
    }
    return results
}

private fun runBacktestForSymbol(
    symbol: String,
    ingest: IngestConfig,
    backtest: BacktestConfig,
    exportDir: File,
    csvSuffix: String?,
    reportLabel: String? = null
): BacktestResult? {
    val dbPath = stackedCandleDbPath(ingest.dbDir, symbol, ingest.interval)
    if (!File(dbPath).exists()) {
        println("Skipping $symbol. Missing DB at $dbPath")
        return null
    }

    val repo = HistoricalDataRepository.create(dbPath)
    val candles = repo.getAllCandles()
    if (candles.isEmpty()) {
        println("Skipping $symbol. No candles found.")
        return null
    }

    val backtester = StackedDcaBacktester(backtest, ingest.interval)
    val events = mutableListOf<BacktestEvent>()
    val debugRecords = mutableListOf<DailyDebugSummary>()
    val result = backtester.run(symbol, candles, events::add, debugRecords::add)
    val suffix = csvSuffix?.let { "_$it" } ?: ""
    val csvFile = File(exportDir, "orders_${symbol.uppercase()}$suffix.csv")
    CsvExporter(csvFile).write(events)
    DebugCsvExporter(File(exportDir, "debug_${symbol.uppercase()}$suffix.csv")).write(debugRecords)
    printDiagnostics(reportLabel, symbol, ingest, backtest, candles, events, debugRecords, result)
    return result
}

private fun stackedCandleDbPath(dbDir: String, symbol: String, interval: String): String {
    return File(dbDir, candleDbPath(symbol, interval)).path
}

private fun printUniverse(symbols: List<SymbolLiquidity>) {
    if (symbols.isEmpty()) {
        println("No symbols matched the universe filter.")
        return
    }

    println("Top symbols by quote volume:")
    symbols.forEachIndexed { index, entry ->
        println("${index + 1}. ${entry.symbol} | quoteVolume=${entry.quoteVolume} | trades=${entry.trades}")
    }
}

private fun printResults(results: List<BacktestResult>) {
    if (results.isEmpty()) {
        println("No results to report.")
        return
    }

    val sorted = results.sortedByDescending { it.totalPnl }
    val rowFormat = "%-10s %10s %10s %10s %9s %7s %8s %8s %8s %11s %16s %10s %10s %10s"
    println(rowFormat.format(Locale.US, "Symbol", "StartEq", "FinalEq", "PnL", "Return%", "Trades", "MaxDD%", "PF", "Exp", "MaxLayers", "MaxLayerTime%", "AvgExp%", "MaxExp%", "MaxRecH"))
    sorted.forEach { r ->
        println(
            rowFormat.format(
                Locale.US,
                r.symbol,
                String.format(Locale.US, "%.2f", r.startEquity),
                String.format(Locale.US, "%.2f", r.finalEquity),
                String.format(Locale.US, "%.2f", r.totalPnl),
                String.format(Locale.US, "%.2f", r.totalReturnPct),
                r.trades,
                String.format(Locale.US, "%.2f", r.maxDrawdownPct),
                String.format(Locale.US, "%.2f", r.profitFactor),
                String.format(Locale.US, "%.2f", r.expectancy),
                r.maxLayersUsed,
                String.format(Locale.US, "%.2f", r.pctTimeAtMaxLayers),
                String.format(Locale.US, "%.2f", r.avgExposurePct),
                String.format(Locale.US, "%.2f", r.maxExposurePct),
                String.format(Locale.US, "%.2f", r.maxRecoveryHours)
            )
        )
    }
}

private fun runSweep(symbols: List<String>, ingest: IngestConfig, baseBacktest: BacktestConfig, sweep: SweepConfig) {
    if (symbols.isEmpty()) {
        println("No symbols to backtest.")
        return
    }

    val presets = buildPresets(baseBacktest, sweep.presetNames)
    if (presets.isEmpty()) {
        println("No sweep presets matched the configured names.")
        return
    }

    val exportDir = File(ingest.exportDir)
    if (!exportDir.exists()) {
        exportDir.mkdirs()
    }

    val sweepResults = mutableListOf<SweepRecord>()
    for (symbol in symbols) {
        presets.forEach { preset ->
            val result = runBacktestForSymbol(
                symbol = symbol,
                ingest = ingest,
                backtest = preset.config,
                exportDir = exportDir,
                csvSuffix = "sweep_${preset.name.lowercase()}",
                reportLabel = preset.name
            )
            if (result != null) {
                sweepResults.add(SweepRecord(symbol, preset.name, result))
            }
        }
    }

    printSweepResults(sweepResults)
}

private fun printSweepResults(results: List<SweepRecord>) {
    if (results.isEmpty()) {
        println("No sweep results to report.")
        return
    }

    val sorted = results.sortedWith(compareByDescending<SweepRecord> { it.result.totalPnl }.thenBy { it.symbol })
    val rowFormat =
        "%-12s %-10s %10s %10s %10s %9s %7s %8s %8s %8s %7s %16s %10s %10s %10s"
    println(
        rowFormat.format(
            Locale.US,
            "Preset",
            "Symbol",
            "StartEq",
            "FinalEq",
            "PnL",
            "Return%",
            "Trades",
            "MaxDD%",
            "PF",
            "Exp",
            "MaxLayers",
            "MaxLayerTime%",
            "AvgExp%",
            "MaxExp%",
            "MaxRecH"
        )
    )
    sorted.forEach { r ->
        println(
            rowFormat.format(
                Locale.US,
                r.presetName,
                r.symbol,
                String.format(Locale.US, "%.2f", r.result.startEquity),
                String.format(Locale.US, "%.2f", r.result.finalEquity),
                String.format(Locale.US, "%.2f", r.result.totalPnl),
                String.format(Locale.US, "%.2f", r.result.totalReturnPct),
                r.result.trades,
                String.format(Locale.US, "%.2f", r.result.maxDrawdownPct),
                String.format(Locale.US, "%.2f", r.result.profitFactor),
                String.format(Locale.US, "%.2f", r.result.expectancy),
                r.result.maxLayersUsed,
                String.format(Locale.US, "%.2f", r.result.pctTimeAtMaxLayers),
                String.format(Locale.US, "%.2f", r.result.avgExposurePct),
                String.format(Locale.US, "%.2f", r.result.maxExposurePct),
                String.format(Locale.US, "%.2f", r.result.maxRecoveryHours)
            )
        )
    }
}

private fun printDiagnostics(
    reportLabel: String?,
    symbol: String,
    ingest: IngestConfig,
    backtest: BacktestConfig,
    candles: List<com.example.platformutil.model.Candle>,
    events: List<BacktestEvent>,
    debugRecords: List<DailyDebugSummary>,
    result: BacktestResult
) {
    val label = reportLabel?.let { "$it | $symbol" } ?: symbol
    println("=== Strategy Report: $label ===")

    if (candles.isEmpty()) {
        println("No candle data available for diagnostics.")
        return
    }

    val startTime = candles.first().openTime
    val endTime = candles.last().openTime
    val intervalMs = intervalToMillis(ingest.interval)
    val expectedCandles = if (intervalMs > 0) ((endTime - startTime) / intervalMs) + 1 else candles.size.toLong()
    val coveragePct = if (expectedCandles > 0) candles.size.toDouble() / expectedCandles * 100.0 else 0.0
    val startDate = Instant.ofEpochMilli(startTime).atZone(ZoneOffset.UTC).toLocalDate()
    val endDate = Instant.ofEpochMilli(endTime).atZone(ZoneOffset.UTC).toLocalDate()

    println(
        "Data: interval=${ingest.interval} candles=${candles.size} expected=${expectedCandles} coverage=${formatPct(coveragePct)} start=$startDate end=$endDate"
    )
    println(
        "Config: volMode=${backtest.volMode} entryAnchor=${backtest.entryAnchor} minVolPct=${formatPct(backtest.minVolPctForEntry * 100.0)} " +
            "maxVolPct=${formatPct(backtest.maxVolPctForEntry * 100.0)} kEntry=${format(backtest.kEntry)} " +
            "minStepPct=${formatPct(backtest.minStepPct * 100.0)} minEntryDist=${formatPct(backtest.minEntryDistPct * 100.0)} " +
            "maxEntryDist=${formatPct(backtest.maxEntryDistPct * 100.0)} kTp=${format(backtest.kTp)} " +
            "kTrail=${format(backtest.kTrail)} kHard=${format(backtest.kHard)} fillMode=${backtest.fillMode} " +
            "sizeMode=${backtest.sizeMode} sizeR=${format(backtest.sizeR)} maxLayers=${backtest.maxLayers} " +
            "maxNotional=${backtest.maxNotional?.let { format(it) } ?: "auto"} trendDays=${backtest.trendDays} " +
            "trendMinPct=${formatPct(backtest.trendMinPct * 100.0)}"
    )

    val days = debugRecords.size
    val entryAttempted = debugRecords.count { it.entryAttempted }
    val entryFilled = debugRecords.count { it.entryFilled }
    val volBelowMin = debugRecords.count { it.comment == "volBelowMin" }
    val trendBlocked = debugRecords.count { it.comment == "trendBelowSma" }
    val volAboveMax = debugRecords.count { it.comment == "volAboveMax" }
    val layerBlocked = debugRecords.count { it.comment == "layerBlocked" }
    val orderExpired = debugRecords.count { it.comment == "orderExpired" }
    val clampedMax = debugRecords.count { it.comment == "limitClampedMax" }
    val clampedMin = debugRecords.count { it.comment == "limitClampedMin" }
    val clampedStep = debugRecords.count { it.comment == "limitClampedStep" }
    val trendActiveDays = debugRecords.count { it.trendSma > 0.0 }
    val trendOkDays = debugRecords.count { it.trendSma > 0.0 && it.trendOk }

    val volValues = debugRecords.map { it.volPct }.filter { it > 0.0 }
    val volAvg = if (volValues.isNotEmpty()) volValues.average() else 0.0
    val volMin = volValues.minOrNull() ?: 0.0
    val volMax = volValues.maxOrNull() ?: 0.0

    val limitDistances = debugRecords.mapNotNull { record ->
        if (record.priceRef > 0.0 && record.limitPrice > 0.0) {
            (record.priceRef - record.limitPrice) / record.priceRef
        } else {
            null
        }
    }
    val limitAvg = if (limitDistances.isNotEmpty()) limitDistances.average() else 0.0
    val limitMin = limitDistances.minOrNull() ?: 0.0
    val limitMax = limitDistances.maxOrNull() ?: 0.0

    val orders = events.count { it.eventType == EventType.ORDER }
    val fills = events.count { it.eventType == EventType.FILL }
    val cancels = events.count { it.eventType == EventType.CANCEL }
    val exits = events.count { it.eventType == EventType.EXIT }
    val fillRate = if (orders > 0) fills.toDouble() / orders * 100.0 else 0.0
    val cancelRate = if (orders > 0) cancels.toDouble() / orders * 100.0 else 0.0
    val exitReasons = events.filter { it.eventType == EventType.EXIT }
        .groupingBy { it.reason }
        .eachCount()
    val tpCount = exitReasons["tp"] ?: 0
    val stopCount = exitReasons["stop"] ?: 0
    val forceCount = exitReasons["force_end"] ?: 0

    val sortedEvents = events.sortedBy { it.timestamp }
    val holdDurations = mutableListOf<Long>()
    var positionOpen: Long? = null
    for (event in sortedEvents) {
        when (event.eventType) {
            EventType.FILL -> if (positionOpen == null) positionOpen = event.timestamp
            EventType.EXIT -> {
                positionOpen?.let { holdDurations.add(event.timestamp - it) }
                positionOpen = null
            }
            else -> {}
        }
    }
    val holdHours = holdDurations.map { it / 3_600_000.0 }
    val avgHold = if (holdHours.isNotEmpty()) holdHours.average() else 0.0
    val medianHold = if (holdHours.isNotEmpty()) median(holdHours) else 0.0

    val totalFees = events.sumOf { it.fee }
    val exitPnl = events.filter { it.eventType == EventType.EXIT }.sumOf { it.pnl }

    println(
        "Gating: days=$days attempted=$entryAttempted filledDays=$entryFilled volBelowMin=$volBelowMin volAboveMax=$volAboveMax " +
            "trendBlocked=$trendBlocked layerBlocked=$layerBlocked orderExpired=$orderExpired clampMax=$clampedMax clampMin=$clampedMin " +
            "clampStep=$clampedStep trendActiveDays=$trendActiveDays trendOkDays=$trendOkDays"
    )
    println(
        "Volatility: avg=${formatPct(volAvg * 100.0)} min=${formatPct(volMin * 100.0)} max=${formatPct(volMax * 100.0)} " +
            "minVolPct=${formatPct(backtest.minVolPctForEntry * 100.0)}"
    )
    println(
        "Limits: avgDist=${formatPct(limitAvg * 100.0)} minDist=${formatPct(limitMin * 100.0)} maxDist=${formatPct(limitMax * 100.0)}"
    )
    println(
        "Execution: orders=$orders fills=$fills cancels=$cancels exits=$exits fillRate=${formatPct(fillRate)} cancelRate=${formatPct(cancelRate)} " +
            "tp=$tpCount stop=$stopCount forceEnd=$forceCount"
    )
    println(
        "Trades: total=${result.trades} wins=${result.wins} losses=${result.losses} winRate=${formatPct(result.wins.toDouble() / maxOf(1, result.trades) * 100.0)} " +
            "avgHoldH=${format(avgHold)} medHoldH=${format(medianHold)} expectancy=${format(result.expectancy)}"
    )
    println(
        "Costs: totalFees=${format(totalFees)} exitPnl=${format(exitPnl)} netPnl=${format(result.totalPnl)}"
    )
    println(
        "Risk: maxDD%=${format(result.maxDrawdownPct)} maxLayers=${result.maxLayersUsed} maxLayerTime%=${format(result.pctTimeAtMaxLayers)} " +
            "avgExp%=${format(result.avgExposurePct)} maxExp%=${format(result.maxExposurePct)} maxRecH=${format(result.maxRecoveryHours)}"
    )

    val actionFlags = buildActionFlags(
        entryAttempted = entryAttempted,
        entryFilled = entryFilled,
        volBelowMin = volBelowMin,
        volAboveMax = volAboveMax,
        trendBlocked = trendBlocked,
        orders = orders,
        fills = fills,
        tpCount = tpCount,
        stopCount = stopCount,
        totalFees = totalFees,
        totalPnl = result.totalPnl
    )
    if (actionFlags.isNotEmpty()) {
        println("ActionFlags:")
        actionFlags.forEach { println(" - $it") }
    }
}

private fun buildActionFlags(
    entryAttempted: Int,
    entryFilled: Int,
    volBelowMin: Int,
    volAboveMax: Int,
    trendBlocked: Int,
    orders: Int,
    fills: Int,
    tpCount: Int,
    stopCount: Int,
    totalFees: Double,
    totalPnl: Double
): List<String> {
    val flags = mutableListOf<String>()
    if (entryAttempted == 0) {
        flags.add("No daily anchors; check candle coverage or interval.")
        return flags
    }

    val volBlockPct = volBelowMin.toDouble() / entryAttempted * 100.0
    if (volBlockPct > 60.0) {
        flags.add("Vol filter blocks ${formatPct(volBlockPct)} of days; lower minVolPct or widen vol mode.")
    }
    val trendBlockPct = trendBlocked.toDouble() / entryAttempted * 100.0
    if (trendBlockPct > 50.0) {
        flags.add("Trend filter blocks ${formatPct(trendBlockPct)} of days; reduce trendDays or trendMinPct.")
    }
    val volHighPct = volAboveMax.toDouble() / entryAttempted * 100.0
    if (volHighPct > 20.0) {
        flags.add("Max-vol filter blocks ${formatPct(volHighPct)} of days; raise maxVolPct or disable the cap.")
    }
    if (orders > 0 && fills == 0) {
        flags.add("No fills; limits too deep or minStep too wide.")
    } else if (orders > 10) {
        val fillRate = fills.toDouble() / orders * 100.0
        if (fillRate < 20.0) {
            flags.add("Low fill rate (${formatPct(fillRate)}); reduce kEntry or minStepPct.")
        }
    }
    if (tpCount + stopCount > 0 && stopCount > tpCount) {
        flags.add("Stops hit more than TPs; consider higher kTp or lower kHard/kTrail.")
    }
    if (totalPnl < 0.0 && totalFees > abs(totalPnl)) {
        flags.add("Fees exceed PnL; reduce trade frequency or raise TP.")
    }
    if (entryFilled == 0) {
        flags.add("No filled entry days; confirm vol thresholds and trend filters.")
    }
    return flags
}

private fun median(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 0) {
        (sorted[mid - 1] + sorted[mid]) / 2.0
    } else {
        sorted[mid]
    }
}

private fun format(value: Double): String = String.format(Locale.US, "%.4f", value)

private fun formatPct(value: Double): String = String.format(Locale.US, "%.2f%%", value)

private data class SweepRecord(
    val symbol: String,
    val presetName: String,
    val result: BacktestResult
)

private fun runPerSymbolPipeline(symbols: List<String>, ingest: IngestConfig, backtest: BacktestConfig) {
    if (symbols.isEmpty()) {
        println("No symbols to ingest or backtest.")
        return
    }

    val results = mutableListOf<BacktestResult>()
    symbols.forEach { symbol ->
        ingestSymbols(listOf(symbol), ingest)
        results.addAll(runBacktests(listOf(symbol), ingest, backtest, shouldPrint = false))
    }

    printResults(results)
}
