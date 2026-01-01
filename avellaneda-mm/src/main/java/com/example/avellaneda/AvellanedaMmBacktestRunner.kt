package com.example.avellaneda

import com.example.account.domain.Price
import com.example.account.domain.Symbol
import com.example.account.domain.inventory.InventoryPosition
import com.example.account.impl.config.InventoryWalletConfig
import com.example.account.impl.inventory.CsvInventoryStateRepository
import com.example.account.impl.inventory.CsvWalletStore
import com.example.execution.impl.SimAccountStateRepository
import com.example.execution.impl.ConservativeFillSimulator
import com.example.execution.impl.SimExecutionGateway
import com.example.marketdata.impl.replay.MarketStateReplayer
import com.example.avellaneda.metrics.AdverseSelectionTracker
import com.example.avellaneda.metrics.FillStats
import com.example.avellaneda.report.AvellanedaCsvReporter
import com.example.avellaneda.report.AvellanedaReportRow
import com.example.execution.impl.ExecutionPolicy
import com.example.execution.impl.IntentAllocator
import com.example.execution.impl.PortfolioEngine
import com.example.execution.impl.RiskBudgetEnv
import com.example.platform.report.ExperimentManifest
import com.example.platform.report.ExperimentManifestWriter
import com.example.platform.report.HealthSummary
import com.example.platform.report.RunSummary
import com.example.platform.report.RunSummaryWriter
import com.example.platform.report.Telemetry
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale
import kotlin.math.abs

object AvellanedaMmBacktestRunner {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        Telemetry.configureFromEnv("avellaneda_backtest", defaultEnabled = true)
        val inputPath = args.getOrNull(0)
            ?: System.getenv("MARKETSTATE_CSV")
            ?: defaultMarketStatePath()
        val symbolArg = args.getOrNull(1)
        val symbolsEnv = System.getenv("SYMBOLS")
        val fastMode = System.getenv("FAST_MODE")?.toBooleanStrictOrNull() ?: false
        val speedup = args.getOrNull(2)?.toDoubleOrNull()
            ?: System.getenv("REPLAY_SPEEDUP")?.toDoubleOrNull()
            ?: if (fastMode) 50.0 else 1.0
        val logEveryMs = System.getenv("LOG_PNL_EVERY_MS")?.toLongOrNull()
            ?: if (fastMode) 300_000L else 60_000L
        val tickLogEvery = System.getenv("LOG_EVERY_TICKS")?.toLongOrNull()
            ?: if (fastMode) 10_000L else 1_000L
        val reportEveryMs = System.getenv("REPORT_EVERY_MS")?.toLongOrNull() ?: 60_000L

        val inputFile = File(inputPath)
        println("Backtest input: ${inputFile.absolutePath}")
        println("Exists       : ${inputFile.exists()} sizeBytes=${if (inputFile.exists()) inputFile.length() else 0L}")
        if (inputFile.exists()) {
            val lineCount = inputFile.useLines { it.count() }
            println("LineCount    : $lineCount")
        }

        val replayer = MarketStateReplayer(File(inputPath), speedup = speedup)
        val accountRepo = SimAccountStateRepository()
        val walletConfig = InventoryWalletConfig.default()
        val inventoryRepo = CsvInventoryStateRepository(CsvWalletStore(walletConfig.walletCsvPath), walletConfig)
        val orderLatencyMs = System.getenv("SIM_ORDER_LATENCY_MS")?.toLongOrNull() ?: 0L
        val queueBuffer = System.getenv("SIM_QUEUE_BUFFER")?.toDoubleOrNull() ?: 1.0
        val queueLevels = System.getenv("SIM_MAX_QUEUE_LEVELS")?.toIntOrNull() ?: 5
        val makerFeePct = System.getenv("MAKER_FEE_PCT")?.toDoubleOrNull() ?: 0.0002
        val takerFeePct = System.getenv("TAKER_FEE_PCT")?.toDoubleOrNull() ?: 0.0004
        val fillSimulator = ConservativeFillSimulator(
            queueBufferMultiplier = queueBuffer,
            maxDepthLevels = queueLevels
        )
        val adverseTracker = AdverseSelectionTracker()
        val reporter = AvellanedaCsvReporter.fromEnv("backtest", adverseTracker.horizonsLabel())
        if (reporter != null) {
            println("ReportPath   : ${reporter.reportPath()}")
            println("ReportEveryMs: $reportEveryMs")
        }
        val telemetryPath = Telemetry.resolveReportPathFromEnv("avellaneda_backtest", defaultEnabled = true)
        val manifestWriter = ExperimentManifestWriter.fromEnv()
        val adverseProvider: (String) -> Double? = { symbol ->
            val adv = adverseTracker.snapshotBps(symbol)
            adv.getOrNull(1) ?: adv.lastOrNull()
        }
        val gateway = SimExecutionGateway(
            accountRepo,
            inventoryRepo,
            fillSimulator,
            orderLatencyMs,
            makerFeePct,
            takerFeePct
        ) { fill ->
            adverseTracker.recordFill(
                fill.symbol.value,
                fill.side,
                fill.price.value.toDouble(),
                fill.fillTimeMs
            )
        }
        val symbols = resolveSymbols(inputPath, symbolArg, symbolsEnv, fastMode)
        val strategies = symbols.map { symbol ->
            val base = AvellanedaMmConfig.default(symbol)
            val config = base.copy(
                orderQty = System.getenv("ORDER_QTY")?.toDoubleOrNull() ?: base.orderQty,
                minSpreadPct = System.getenv("MIN_SPREAD_PCT")?.toDoubleOrNull() ?: base.minSpreadPct,
                inventorySkew = System.getenv("INVENTORY_SKEW")?.toDoubleOrNull() ?: base.inventorySkew,
                maxInventory = System.getenv("MAX_INVENTORY")?.toDoubleOrNull() ?: base.maxInventory,
                priceTick = System.getenv("PRICE_TICK")?.toDoubleOrNull() ?: base.priceTick,
                qtyStep = System.getenv("QTY_STEP")?.toDoubleOrNull() ?: base.qtyStep,
                quoteRefreshMs = System.getenv("QUOTE_REFRESH_MS")?.toLongOrNull() ?: base.quoteRefreshMs,
                maxQuoteAgeMs = System.getenv("MAX_QUOTE_AGE_MS")?.toLongOrNull() ?: base.maxQuoteAgeMs,
                gateCooldownMs = System.getenv("GATE_COOLDOWN_MS")?.toLongOrNull() ?: base.gateCooldownMs,
                spreadWindowMs = System.getenv("SPREAD_WINDOW_MS")?.toLongOrNull() ?: base.spreadWindowMs,
                minAvgSpreadPct = System.getenv("MIN_AVG_SPREAD_PCT")?.toDoubleOrNull() ?: base.minAvgSpreadPct,
                maxAvgSpreadPct = System.getenv("MAX_AVG_SPREAD_PCT")?.toDoubleOrNull() ?: base.maxAvgSpreadPct,
                maxSpreadPct = System.getenv("MAX_SPREAD_PCT")?.toDoubleOrNull() ?: base.maxSpreadPct,
                minTopDepth = System.getenv("MIN_TOP_DEPTH")?.toDoubleOrNull() ?: base.minTopDepth,
                topDepthLevels = System.getenv("TOP_DEPTH_LEVELS")?.toIntOrNull() ?: base.topDepthLevels,
                maxDepthImbalance = System.getenv("MAX_DEPTH_IMBALANCE")?.toDoubleOrNull() ?: base.maxDepthImbalance,
                maxTradeImbalance1s = System.getenv("MAX_TRADE_IMBALANCE_1S")?.toDoubleOrNull() ?: base.maxTradeImbalance1s,
                minTradeCount1sForToxicity = System.getenv("MIN_TRADE_COUNT_1S")?.toIntOrNull() ?: base.minTradeCount1sForToxicity,
                maxVol1s = System.getenv("MAX_VOL_1S")?.toDoubleOrNull() ?: base.maxVol1s,
                maxVol5s = System.getenv("MAX_VOL_5S")?.toDoubleOrNull() ?: base.maxVol5s,
                maxVol10s = System.getenv("MAX_VOL_10S")?.toDoubleOrNull() ?: base.maxVol10s,
                volSpreadMultiplier = System.getenv("VOL_SPREAD_MULT")?.toDoubleOrNull() ?: base.volSpreadMultiplier,
                adaptiveSpreadTargetBps = System.getenv("ADAPTIVE_SPREAD_TARGET_BPS")?.toDoubleOrNull()
                    ?: base.adaptiveSpreadTargetBps,
                adaptiveSpreadUpdateMs = System.getenv("ADAPTIVE_SPREAD_UPDATE_MS")?.toLongOrNull()
                    ?: base.adaptiveSpreadUpdateMs,
                toxicitySpikeAdvBps = System.getenv("TOXICITY_SPIKE_ADV_BPS")?.toDoubleOrNull()
                    ?: base.toxicitySpikeAdvBps,
                turtleStrikeWindowMs = System.getenv("TURTLE_STRIKE_WINDOW_MS")?.toLongOrNull()
                    ?: base.turtleStrikeWindowMs,
                turtleStrikeThreshold = System.getenv("TURTLE_STRIKE_THRESHOLD")?.toIntOrNull()
                    ?: base.turtleStrikeThreshold,
                turtlePauseMs = System.getenv("TURTLE_PAUSE_MS")?.toLongOrNull()
                    ?: base.turtlePauseMs,
                quoteStyle = parseQuoteStyle(System.getenv("QUOTE_STYLE")),
                logGateDecisions = System.getenv("LOG_GATES")?.toBooleanStrictOrNull() ?: base.logGateDecisions,
                makerFeePct = System.getenv("MAKER_FEE_PCT")?.toDoubleOrNull() ?: base.makerFeePct
            )
            if (System.getenv("LOG_CONFIG")?.toBooleanStrictOrNull() == true) {
                println("config[$symbol]=$config")
            }
            AvellanedaMmIntentStrategy(config = config, adverseBpsProvider = adverseProvider)
        }
        val allocator = IntentAllocator(
            riskBudget = RiskBudgetEnv.fromEnv(
                defaultTotal = 1e12,
                defaultShares = mapOf("avellaneda_mm" to 1.0)
            )
        )
        val policy = ExecutionPolicy(gateway)
        val engine = PortfolioEngine(gateway, allocator, policy, strategies)
        val reportPath = telemetryPath ?: reporter?.reportPath()
        manifestWriter?.write(
            ExperimentManifest(
                timestampMs = System.currentTimeMillis(),
                strategy = "avellaneda",
                mode = "backtest",
                symbols = symbols,
                params = mapOf(
                    "MARKETSTATE_CSV" to inputPath,
                    "REPLAY_SPEEDUP" to speedup.toString(),
                    "FAST_MODE" to fastMode.toString(),
                    "QUOTE_STYLE" to parseQuoteStyle(System.getenv("QUOTE_STYLE")).name,
                    "ORDER_QTY" to (System.getenv("ORDER_QTY") ?: ""),
                    "MAX_INVENTORY" to (System.getenv("MAX_INVENTORY") ?: ""),
                    "MIN_AVG_SPREAD_PCT" to (System.getenv("MIN_AVG_SPREAD_PCT") ?: ""),
                    "MAX_AVG_SPREAD_PCT" to (System.getenv("MAX_AVG_SPREAD_PCT") ?: ""),
                    "ADAPTIVE_SPREAD_TARGET_BPS" to (System.getenv("ADAPTIVE_SPREAD_TARGET_BPS") ?: ""),
                    "ADAPTIVE_SPREAD_UPDATE_MS" to (System.getenv("ADAPTIVE_SPREAD_UPDATE_MS") ?: ""),
                    "MAKER_FEE_PCT" to makerFeePct.toString(),
                    "TAKER_FEE_PCT" to takerFeePct.toString()
                ).filterValues { it.isNotBlank() },
                reportPath = reportPath,
                runId = System.getenv("RUN_ID"),
                notes = System.getenv("RUN_NOTES")
            )
        )
        var ticks = 0L
        var lastPnlLog = 0L
        var lastFillTotal = 0
        var lastReportLog = 0L
        val latestMid = mutableMapOf<String, Double>()
        replayer.stream().collect { state ->
            if (state.symbol !in symbols) return@collect
            gateway.onMarketState(state)
            engine.onMarketState(state)
            val mid = state.midPrice ?: state.lastTradePrice
            if (mid != null) {
                latestMid[state.symbol] = mid
            }
            updateMarkPrice(
                inventoryRepo,
                state.symbol,
                mid,
                state.eventTimeMs ?: state.timestampMs
            )
            ticks++
            adverseTracker.onMarketState(state)
            val now = state.eventTimeMs ?: state.timestampMs
            if (lastPnlLog == 0L) lastPnlLog = now
            if (lastReportLog == 0L) lastReportLog = now
            if (now - lastPnlLog >= logEveryMs) {
                logPnlSummary(inventoryRepo)
                val fillStats = computeFillStats(accountRepo.allFills(), makerFeePct)
                logHealthSummary(
                    inventoryRepo,
                    accountRepo,
                    lastFillTotal,
                    lastPnlLog,
                    now,
                    adverseTracker,
                    fillStats
                )
                lastFillTotal = accountRepo.totalFills()
                lastPnlLog = now
            }
            if (reporter != null && now - lastReportLog >= reportEveryMs) {
                val rows = buildReportRows(
                    symbols,
                    inventoryRepo,
                    latestMid,
                    adverseTracker,
                    accountRepo.allFills(),
                    makerFeePct,
                    takerFeePct,
                    now
                )
                reporter.write(rows)
                lastReportLog = now
            }
            if (ticks % tickLogEvery == 0L) {
                println("ticks=$ticks last=${state.symbol} t=${state.timestampMs}")
            }
        }

        inventoryRepo.persist()
        println("finished ticks=$ticks")
        val fillStats = computeFillStats(accountRepo.allFills(), makerFeePct)
        writeAvellanedaSummary(
            inputPath = inputPath,
            symbols = symbols,
            inventoryRepo = inventoryRepo,
            accountRepo = accountRepo,
            fillStats = fillStats,
            adverseTracker = adverseTracker,
            reporterPath = reporter?.reportPath(),
            telemetryPath = telemetryPath,
            fastMode = fastMode,
            speedup = speedup,
            reportEveryMs = reportEveryMs,
            makerFeePct = makerFeePct,
            takerFeePct = takerFeePct
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

    private fun resolveSymbols(
        inputPath: String,
        symbolArg: String?,
        symbolsEnv: String?,
        fastMode: Boolean
    ): List<String> {
        val raw = symbolArg?.ifBlank { null } ?: symbolsEnv?.ifBlank { null }
        if (raw != null) {
            return raw.split(',').map { it.trim().uppercase() }.filter { it.isNotBlank() }
        }
        val symbols = loadSymbolsFromFile(File(inputPath))
        return if (fastMode) symbols.take(1) else symbols
    }

    private fun loadSymbolsFromFile(file: File): List<String> {
        if (!file.exists()) return emptyList()
        val lines = file.readLines()
        if (lines.size <= 1) return emptyList()
        return lines.drop(1)
            .mapNotNull { line -> line.split(',').firstOrNull()?.trim()?.uppercase() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private suspend fun updateMarkPrice(
        inventoryRepo: CsvInventoryStateRepository,
        symbol: String,
        markPrice: Double?,
        timestampMs: Long
    ) {
        if (markPrice == null) return
        inventoryRepo.applyMarkPrice(Symbol.of(symbol), Price.fromDouble(markPrice), timestampMs)
    }

    private suspend fun logPnlSummary(inventoryRepo: CsvInventoryStateRepository) {
        val positions = inventoryRepo.getInventory()
        if (positions.isEmpty()) {
            println("pnl: no positions")
            return
        }
        val header = String.format(
            "%-10s %12s %12s %12s %12s %12s %8s",
            "SYMBOL",
            "QTY",
            "AVG",
            "UNR_PNL",
            "REAL_PNL",
            "PNL",
            "PNL%"
        )
        val line = "-".repeat(header.length)
        println(line)
        println(header)
        println(line)

        var totalExposure = 0.0
        var totalRealized = 0.0
        var totalUnrealized = 0.0

        positions.sortedBy { it.symbol.value }.forEach { p ->
            val qty = p.quantity.value.toDouble()
            val avg = p.avgPrice.value.toDouble()
            val unrealized = p.unrealizedPnl.value.toDouble()
            val realized = p.realizedPnl.value.toDouble()
            val pnl = realized + unrealized
            val exposure = kotlin.math.abs(qty * avg)
            totalExposure += exposure
            totalRealized += realized
            totalUnrealized += unrealized
            val pnlPct = if (exposure > 0.0) pnl / exposure * 100.0 else 0.0
            println(
                String.format(
                    "%-10s %12.6f %12.6f %12.4f %12.4f %12.4f %7.2f%%",
                    p.symbol.value,
                    qty,
                    avg,
                    unrealized,
                    realized,
                    pnl,
                    pnlPct
                )
            )
        }

        val totalPnl = totalRealized + totalUnrealized
        val totalPct = if (totalExposure > 0.0) totalPnl / totalExposure * 100.0 else 0.0
        println(line)
        println(
            String.format(
                "%-10s %12s %12s %12.4f %12.4f %12.4f %7.2f%%",
                "TOTAL",
                "",
                "",
                totalUnrealized,
                totalRealized,
                totalPnl,
                totalPct
            )
        )
        println(line)
    }

    private suspend fun logHealthSummary(
        inventoryRepo: CsvInventoryStateRepository,
        accountRepo: SimAccountStateRepository,
        lastFillTotal: Int,
        lastLogMs: Long,
        nowMs: Long,
        adverseTracker: AdverseSelectionTracker,
        fillStats: FillStats
    ) {
        val elapsedSec = ((nowMs - lastLogMs).coerceAtLeast(1L)) / 1000.0
        val totalFills = accountRepo.totalFills()
        val fillsPerMin = (totalFills - lastFillTotal) * (60.0 / elapsedSec)
        val positions = inventoryRepo.getInventory()
        val maxAbsQty = positions.maxOfOrNull { kotlin.math.abs(it.quantity.value.toDouble()) } ?: 0.0
        val sumAbsQty = positions.sumOf { kotlin.math.abs(it.quantity.value.toDouble()) }
        val labels = adverseTracker.horizonsLabel()
        val advStr = positions.sortedBy { it.symbol.value }.joinToString(" ") { p ->
            val adv = adverseTracker.snapshotBps(p.symbol.value)
            val parts = labels.zip(adv).joinToString(",") { (label, value) ->
                val v = value?.let { "%.2f".format(it) } ?: "NA"
                "adv${label}=${v}"
            }
            "${p.symbol.value}[$parts]"
        }
        println(
            String.format(
                "health: fillsPerMin=%.2f positions=%d maxAbsQty=%.6f sumAbsQty=%.6f",
                fillsPerMin,
                positions.size,
                maxAbsQty,
                sumAbsQty
            )
        )
        println(
            String.format(
                "fills: total=%d maker=%d taker=%d notional=%.4f fees=%.4f",
                fillStats.makerCount + fillStats.takerCount,
                fillStats.makerCount,
                fillStats.takerCount,
                fillStats.totalNotional,
                fillStats.totalFees
            )
        )
        println("adv: $advStr")
        val totalNet = positions.sumOf { it.realizedPnl.value.toDouble() + it.unrealizedPnl.value.toDouble() }
        val totalExposure = positions.sumOf { abs(it.quantity.value.toDouble() * it.avgPrice.value.toDouble()) }
        val avgAdv = positions.mapNotNull { adverseTracker.snapshotBps(it.symbol.value).firstOrNull() }
            .let { if (it.isEmpty()) null else it.average() }
        val netBps = if (fillStats.totalNotional > 0.0) {
            (totalNet / fillStats.totalNotional) * 10_000.0
        } else {
            0.0
        }
        val advAverages = averageAdvByLabel(positions, labels, adverseTracker)
        println(
            HealthSummary.render(
                strategy = "avellaneda",
                mode = "backtest",
                net = totalNet,
                fees = fillStats.totalFees,
                adverseBps = avgAdv,
                fills = fillStats.totalCount(),
                exposure = totalExposure,
                extra = buildMap {
                    put("fills_per_min", formatDouble(fillsPerMin, 2))
                    put("max_abs_qty", formatDouble(maxAbsQty, 6))
                    put("sum_abs_qty", formatDouble(sumAbsQty, 6))
                    put("maker_fills", fillStats.makerCount.toString())
                    put("taker_fills", fillStats.takerCount.toString())
                    put("total_notional", formatDouble(fillStats.totalNotional, 4))
                    put("net_bps", formatDouble(netBps, 3))
                    advAverages.forEach { (label, value) ->
                        put("adv_${label}_bps", formatDouble(value, 3))
                    }
                }
            )
        )
    }

    private fun averageAdvByLabel(
        positions: List<InventoryPosition>,
        labels: List<String>,
        adverseTracker: AdverseSelectionTracker
    ): Map<String, Double> {
        return labels.mapIndexedNotNull { index, label ->
            val values = positions.mapNotNull {
                adverseTracker.snapshotBps(it.symbol.value).getOrNull(index)
            }
            if (values.isEmpty()) null else label to values.average()
        }.toMap()
    }

    private fun formatDouble(value: Double, decimals: Int): String {
        return String.format(Locale.US, "%.${decimals}f", value)
    }

    private fun parseQuoteStyle(raw: String?): QuoteStyle {
        return when (raw?.trim()?.uppercase()) {
            "IMPROVE" -> QuoteStyle.IMPROVE
            "WIDEN" -> QuoteStyle.WIDEN
            else -> QuoteStyle.JOIN
        }
    }

    private fun computeFillStats(
        fills: List<com.example.account.domain.Fill>,
        makerFeePct: Double
    ): FillStats {
        val stats = FillStats()
        fills.forEach { fill ->
            val notional = fill.price.value.toDouble() * fill.quantity.value.toDouble()
            stats.record(notional, makerFeePct, 0.0, isMaker = true)
        }
        return stats
    }

    private suspend fun writeAvellanedaSummary(
        inputPath: String,
        symbols: List<String>,
        inventoryRepo: CsvInventoryStateRepository,
        accountRepo: SimAccountStateRepository,
        fillStats: FillStats,
        adverseTracker: AdverseSelectionTracker,
        reporterPath: String?,
        telemetryPath: String?,
        fastMode: Boolean,
        speedup: Double,
        reportEveryMs: Long,
        makerFeePct: Double,
        takerFeePct: Double
    ) {
        val positions = inventoryRepo.getInventory()
        val totalRealized = positions.sumOf { it.realizedPnl.value.toDouble() }
        val totalUnrealized = positions.sumOf { it.unrealizedPnl.value.toDouble() }
        val totalNet = totalRealized + totalUnrealized
        val totalExposure = positions.sumOf { abs(it.quantity.value.toDouble() * it.avgPrice.value.toDouble()) }
        val avgAdv = positions.mapNotNull { adverseTracker.snapshotBps(it.symbol.value).firstOrNull() }
            .let { if (it.isEmpty()) null else it.average() }
        val configs = mapOf(
            "MARKETSTATE_CSV" to inputPath,
            "SYMBOLS" to symbols.joinToString(","),
            "FAST_MODE" to fastMode.toString(),
            "REPLAY_SPEEDUP" to speedup.toString(),
            "REPORT_EVERY_MS" to reportEveryMs.toString(),
            "MAKER_FEE_PCT" to makerFeePct.toString(),
            "TAKER_FEE_PCT" to takerFeePct.toString()
        ).filterValues { it.isNotBlank() }
        RunSummaryWriter.writeSummary(
            root = findProjectRoot(),
            summary = RunSummary(
                strategy = "avellaneda",
                mode = "backtest",
                timestampMs = System.currentTimeMillis(),
                configs = configs,
                metrics = mapOf(
                    "net" to totalNet,
                    "realized" to totalRealized,
                    "unrealized" to totalUnrealized,
                    "exposure" to totalExposure,
                    "maker_fills" to fillStats.makerCount,
                    "taker_fills" to fillStats.takerCount,
                    "total_fees" to fillStats.totalFees,
                    "total_notional" to fillStats.totalNotional,
                    "avg_adv_bps" to avgAdv
                ),
                health = mapOf(
                    "positions" to positions.size,
                    "total_qty" to positions.sumOf { it.quantity.value.toDouble() },
                    "max_abs_qty" to positions.maxOfOrNull { abs(it.quantity.value.toDouble()) },
                    "sum_abs_qty" to positions.sumOf { abs(it.quantity.value.toDouble()) },
                    "fills_total" to accountRepo.totalFills()
                ),
                notes = mapOfNotNulls(
                    "reporter_path" to reporterPath,
                    "telemetry_path" to telemetryPath,
                    "run_id" to System.getenv("RUN_ID"),
                    "run_notes" to System.getenv("RUN_NOTES")
                )
            )
        )
    }

    private fun mapOfNotNulls(vararg pairs: Pair<String, String?>): Map<String, String> {
        return pairs.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
    }

    private suspend fun buildReportRows(
        symbols: List<String>,
        inventoryRepo: CsvInventoryStateRepository,
        latestMid: Map<String, Double>,
        adverseTracker: AdverseSelectionTracker,
        fills: List<com.example.account.domain.Fill>,
        makerFeePct: Double,
        takerFeePct: Double,
        nowMs: Long
    ): List<AvellanedaReportRow> {
        val positions = inventoryRepo.getInventory().associateBy { it.symbol.value }
        val statsBySymbol = mutableMapOf<String, FillStats>()
        val totalStats = FillStats()
        fills.forEach { fill ->
            val notional = fill.price.value.toDouble() * fill.quantity.value.toDouble()
            val stats = statsBySymbol.getOrPut(fill.symbol.value) { FillStats() }
            stats.record(notional, makerFeePct, takerFeePct, isMaker = fill.isBuyerMaker)
            totalStats.record(notional, makerFeePct, takerFeePct, isMaker = fill.isBuyerMaker)
        }

        val rows = symbols.sorted().map { symbol ->
            val pos = positions[symbol]
            val qty = pos?.quantity?.value?.toDouble() ?: 0.0
            val avg = pos?.avgPrice?.value?.toDouble() ?: 0.0
            val unrealized = pos?.unrealizedPnl?.value?.toDouble() ?: 0.0
            val realized = pos?.realizedPnl?.value?.toDouble() ?: 0.0
            val net = realized + unrealized
            val exposure = abs(qty * avg)
            val pnlPct = if (exposure > 0.0) net / exposure * 100.0 else 0.0
            val stats = statsBySymbol[symbol]
            AvellanedaReportRow(
                timestampMs = nowMs,
                symbol = symbol,
                mid = latestMid[symbol],
                qty = qty,
                avg = avg,
                unrealizedPnl = unrealized,
                realizedPnl = realized,
                netPnl = net,
                pnlPct = pnlPct,
                exposure = exposure,
                fills = stats?.totalCount() ?: 0,
                makerFills = stats?.makerCount,
                takerFills = stats?.takerCount,
                totalFees = stats?.totalFees,
                totalNotional = stats?.totalNotional,
                advBps = adverseTracker.snapshotBps(symbol)
            )
        }.toMutableList()

        val totals = positions.values
        val totalExposure = totals.sumOf { abs(it.quantity.value.toDouble() * it.avgPrice.value.toDouble()) }
        val totalRealized = totals.sumOf { it.realizedPnl.value.toDouble() }
        val totalUnrealized = totals.sumOf { it.unrealizedPnl.value.toDouble() }
        val totalNet = totalRealized + totalUnrealized
        val totalPct = if (totalExposure > 0.0) totalNet / totalExposure * 100.0 else 0.0
        rows.add(
            AvellanedaReportRow(
                timestampMs = nowMs,
                symbol = "TOTAL",
                mid = null,
                qty = 0.0,
                avg = 0.0,
                unrealizedPnl = totalUnrealized,
                realizedPnl = totalRealized,
                netPnl = totalNet,
                pnlPct = totalPct,
                exposure = totalExposure,
                fills = totalStats.totalCount(),
                makerFills = totalStats.makerCount,
                takerFills = totalStats.takerCount,
                totalFees = totalStats.totalFees,
                totalNotional = totalStats.totalNotional,
                advBps = emptyList()
            )
        )
        return rows
    }
}
