package com.example.ofi.kukanov

import com.example.account.domain.Price
import com.example.account.domain.Symbol
import com.example.account.impl.config.InventoryWalletConfig
import com.example.account.impl.inventory.CsvInventoryStateRepository
import com.example.account.impl.inventory.CsvWalletStore
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
import com.example.platform.report.HealthSummary
import com.example.platform.report.RunSummary
import com.example.platform.report.RunSummaryWriter
import com.example.platform.report.Telemetry
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.milliseconds
import java.io.File

object OfiBacktestRunner {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        Telemetry.configureFromEnv("ofi_backtest", defaultEnabled = true)
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
        println("OFI backtest input: ${inputFile.absolutePath}")
        println("Exists           : ${inputFile.exists()} sizeBytes=${if (inputFile.exists()) inputFile.length() else 0L}")
        if (inputFile.exists()) {
            val lineCount = inputFile.useLines { it.count() }
            println("LineCount        : $lineCount")
        }

        val replayer = MarketStateReplayer(File(inputPath), speedup = speedup)
        val accountRepo = SimAccountStateRepository()
        val walletConfig = InventoryWalletConfig.default()
        val inventoryRepo = CsvInventoryStateRepository(CsvWalletStore(walletConfig.walletCsvPath), walletConfig)
        val orderLatencyMs = System.getenv("SIM_ORDER_LATENCY_MS")?.toLongOrNull() ?: 100L
        val queueBuffer = System.getenv("SIM_QUEUE_BUFFER")?.toDoubleOrNull() ?: 1.0
        val queueLevels = System.getenv("SIM_MAX_QUEUE_LEVELS")?.toIntOrNull() ?: 5
        val makerFeePct = System.getenv("MAKER_FEE_PCT")?.toDoubleOrNull() ?: 0.0002
        val takerFeePct = System.getenv("TAKER_FEE_PCT")?.toDoubleOrNull() ?: 0.0004
        val fillSimulator = ConservativeFillSimulator(
            queueBufferMultiplier = queueBuffer,
            maxDepthLevels = queueLevels
        )
        val kpiTracker = OfiKpiTracker(makerFeePct, takerFeePct)
        val gateway = SimExecutionGateway(
            accountRepo,
            inventoryRepo,
            fillSimulator,
            orderLatencyMs,
            makerFeePct,
            takerFeePct,
            fillListener = { fill -> kpiTracker.onFill(fill) }
        )

        val symbols = resolveSymbols(inputPath, symbolArg, symbolsEnv, fastMode)
        val ofiConfig = kukanovConfigFromEnv()
        val strategies = symbols.map { symbol ->
            OfiKukanovIntentStrategy(
                config = strategyConfigFromEnv(symbol),
                signalConfig = ofiConfig
            )
        }
        val allocator = IntentAllocator(
            riskBudget = RiskBudgetEnv.fromEnv(
                defaultTotal = 1e12,
                defaultShares = mapOf("ofi_kukanov" to 1.0)
            )
        )
        val policy = ExecutionPolicy(gateway)
        val engine = PortfolioEngine(gateway, allocator, policy, strategies)
        val telemetryPath = Telemetry.resolveReportPathFromEnv("ofi_backtest", defaultEnabled = true)
        val manifestWriter = ExperimentManifestWriter.fromEnv()
        manifestWriter?.write(
            ExperimentManifest(
                timestampMs = System.currentTimeMillis(),
                strategy = "ofi-kukanov",
                mode = "backtest",
                symbols = symbols,
                params = mapOf(
                    "MARKETSTATE_CSV" to inputPath,
                    "REPLAY_SPEEDUP" to speedup.toString(),
                    "OFI_WINDOW_MS" to (System.getenv("OFI_WINDOW_MS") ?: ""),
                    "OFI_ENTRY_THRESHOLD" to (System.getenv("OFI_ENTRY_THRESHOLD") ?: ""),
                    "OFI_EXIT_THRESHOLD" to (System.getenv("OFI_EXIT_THRESHOLD") ?: ""),
                    "ORDER_STYLE" to (System.getenv("ORDER_STYLE") ?: "")
                ).filterValues { it.isNotBlank() },
                reportPath = telemetryPath,
                runId = System.getenv("RUN_ID"),
                notes = System.getenv("RUN_NOTES")
            )
        )
        var ticks = 0L
        var lastKpiLogMs = 0L
        replayer.stream().collect { state ->
            if (symbols.contains(state.symbol).not()) return@collect
            gateway.onMarketState(state)
            engine.onMarketState(state)
            kpiTracker.onMarketState(
                state.symbol,
                state.midPrice ?: state.lastTradePrice,
                state.eventTimeMs ?: state.timestampMs
            )
            updateMarkPrice(
                inventoryRepo,
                state.symbol,
                state.midPrice ?: state.lastTradePrice,
                state.eventTimeMs ?: state.timestampMs
            )
            ticks++
            val now = state.eventTimeMs ?: state.timestampMs
            if (ticks % logEvery == 0L) {
                println("ticks=$ticks last=${state.symbol} t=${state.timestampMs}")
            }
            if (lastKpiLogMs == 0L) lastKpiLogMs = now
            if (now - lastKpiLogMs >= kpiEveryMs) {
                val summary = kpiTracker.summary()
                logKpis(summary)
                lastKpiLogMs = now
            }
        }

        inventoryRepo.persist()
        println("finished ticks=$ticks")
        val finalSummary = kpiTracker.summary()
        writeOfiSummary(
            symbols = symbols,
            summary = finalSummary,
            mode = "backtest",
            telemetryPath = telemetryPath,
            manifestPath = manifestWriter?.path(),
            configs = mapOf(
                "MARKETSTATE_CSV" to inputPath,
                "REPLAY_SPEEDUP" to speedup.toString(),
                "OFI_WINDOW_MS" to (System.getenv("OFI_WINDOW_MS") ?: ""),
                "OFI_ENTRY_THRESHOLD" to (System.getenv("OFI_ENTRY_THRESHOLD") ?: ""),
                "OFI_EXIT_THRESHOLD" to (System.getenv("OFI_EXIT_THRESHOLD") ?: ""),
                "ORDER_STYLE" to (System.getenv("ORDER_STYLE") ?: "")
            )
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

    private fun kukanovConfigFromEnv(): KukanovOfiConfig {
        val windowMs = System.getenv("OFI_WINDOW_MS")?.toLongOrNull()
        val depthLevels = System.getenv("OFI_DEPTH_LEVELS")?.toIntOrNull()
        return KukanovOfiConfig(
            window = (windowMs ?: 1_000L).milliseconds,
            depthLevels = depthLevels ?: 1
        )
    }

    private fun strategyConfigFromEnv(symbol: String): OfiStrategyConfig {
        val base = OfiStrategyConfig(symbol = symbol)
        return base.copy(
            orderQty = System.getenv("ORDER_QTY")?.toDoubleOrNull() ?: base.orderQty,
            priceTick = System.getenv("PRICE_TICK")?.toDoubleOrNull() ?: base.priceTick,
            qtyStep = System.getenv("QTY_STEP")?.toDoubleOrNull() ?: base.qtyStep,
            entryThreshold = System.getenv("OFI_ENTRY_THRESHOLD")?.toDoubleOrNull() ?: base.entryThreshold,
            exitThreshold = System.getenv("OFI_EXIT_THRESHOLD")?.toDoubleOrNull() ?: base.exitThreshold,
            takeThresholdMultiplier = System.getenv("OFI_TAKE_MULT")?.toDoubleOrNull()
                ?: base.takeThresholdMultiplier,
            takeMinEdgeBps = System.getenv("OFI_TAKE_MIN_EDGE_BPS")?.toDoubleOrNull()
                ?: base.takeMinEdgeBps,
            maxHoldMs = System.getenv("OFI_MAX_HOLD_MS")?.toLongOrNull() ?: base.maxHoldMs,
            minSignalIntervalMs = System.getenv("OFI_MIN_SIGNAL_MS")?.toLongOrNull() ?: base.minSignalIntervalMs,
            orderTtlMs = System.getenv("ORDER_TTL_MS")?.toLongOrNull() ?: base.orderTtlMs,
            takeOrderTtlMs = System.getenv("TAKE_ORDER_TTL_MS")?.toLongOrNull() ?: base.takeOrderTtlMs,
            spreadWindowMs = System.getenv("SPREAD_WINDOW_MS")?.toLongOrNull() ?: base.spreadWindowMs,
            maxSpreadPct = System.getenv("MAX_SPREAD_PCT")?.toDoubleOrNull() ?: base.maxSpreadPct,
            maxSpreadDeviationPct = System.getenv("SPREAD_DEV_PCT")?.toDoubleOrNull()
                ?: base.maxSpreadDeviationPct,
            minSpreadSamples = System.getenv("SPREAD_MIN_SAMPLES")?.toIntOrNull() ?: base.minSpreadSamples,
            minDepthNotional = System.getenv("MIN_DEPTH_NOTIONAL")?.toDoubleOrNull()
                ?: base.minDepthNotional,
            minDepthQty = System.getenv("MIN_DEPTH_QTY")?.toDoubleOrNull() ?: base.minDepthQty,
            useTradeConfirm = System.getenv("USE_TRADE_CONFIRM")?.toBooleanStrictOrNull()
                ?: base.useTradeConfirm,
            minTradeCount1s = System.getenv("MIN_TRADE_COUNT_1S")?.toIntOrNull()
                ?: base.minTradeCount1s,
            minTradeImbalance1s = System.getenv("MIN_TRADE_IMB_1S")?.toDoubleOrNull()
                ?: base.minTradeImbalance1s,
            joinOffsetTicks = System.getenv("JOIN_OFFSET_TICKS")?.toIntOrNull() ?: base.joinOffsetTicks,
            orderStyle = parseOrderStyle(System.getenv("ORDER_STYLE")),
            logSignals = System.getenv("LOG_SIGNALS")?.toBooleanStrictOrNull() ?: base.logSignals
        )
    }

    private fun parseOrderStyle(raw: String?): OfiOrderStyle {
        return when (raw?.trim()?.uppercase()) {
            "TAKE" -> OfiOrderStyle.TAKE
            else -> OfiOrderStyle.JOIN
        }
    }

    private fun logKpis(summary: OfiKpiSummary) {
        Telemetry.emit(
            type = "kpi_snapshot",
            tsMs = System.currentTimeMillis(),
            data = mapOf(
                "strategy_id" to "ofi_kukanov",
                "mode" to "backtest",
                "realized_pnl" to summary.realizedPnl,
                "unrealized_pnl" to summary.unrealizedPnl,
                "total_fees" to summary.totalFees,
                "net_pnl" to summary.netPnl,
                "avg_slippage_bps" to summary.avgSlippageBps,
                "avg_join_edge_bps" to summary.avgJoinEdgeBps,
                "avg_adverse_bps" to summary.avgAdverseMoveBps,
                "avg_latency_ms" to summary.avgLatencyMs,
                "trade_count" to summary.tradeCount,
                "win_rate" to summary.winRate,
                "fill_rate" to summary.fillRate,
                "cancel_rate" to summary.cancelRate,
                "stale_cancel_rate" to summary.staleCancelRate,
                "take_rate" to summary.takeRate
            )
        )
        val winRate = summary.winRate?.let { "%.2f%%".format(it * 100.0) } ?: "NA"
        val slip = summary.avgSlippageBps?.let { "%.3f".format(it) } ?: "NA"
        val joinEdge = summary.avgJoinEdgeBps?.let { "%.3f".format(it) } ?: "NA"
        val adverse = summary.avgAdverseMoveBps?.let { "%.3f".format(it) } ?: "NA"
        val latency = summary.avgLatencyMs?.let { "%.1f".format(it) } ?: "NA"
        val fillRate = summary.fillRate?.let { "%.2f%%".format(it * 100.0) } ?: "NA"
        val cancelRate = summary.cancelRate?.let { "%.2f%%".format(it * 100.0) } ?: "NA"
        val staleRate = summary.staleCancelRate?.let { "%.2f%%".format(it * 100.0) } ?: "NA"
        val takeRate = summary.takeRate?.let { "%.2f%%".format(it * 100.0) } ?: "NA"
        println(
            "kpi: net=${"%.4f".format(summary.netPnl)} realized=${"%.4f".format(summary.realizedPnl)} " +
                "unrealized=${"%.4f".format(summary.unrealizedPnl)} fees=${"%.4f".format(summary.totalFees)} " +
                "joinEdgeBps=$joinEdge takeSlipBps=$slip adverseBps=$adverse latencyMs=$latency " +
                "trades=${summary.tradeCount} winRate=$winRate fillRate=$fillRate cancelRate=$cancelRate " +
                "staleCancelRate=$staleRate takeRate=$takeRate"
        )
        println(
            HealthSummary.render(
                strategy = "ofi-kukanov",
                mode = "backtest",
                net = summary.netPnl,
                fees = summary.totalFees,
                adverseBps = summary.avgAdverseMoveBps,
                fills = summary.tradeCount,
                exposure = null,
                extra = mapOf(
                    "win_rate" to winRate,
                    "fill_rate" to fillRate,
                    "cancel_rate" to cancelRate,
                    "stale_cancel_rate" to staleRate,
                    "take_rate" to takeRate,
                    "avg_slippage_bps" to slip,
                    "avg_join_edge_bps" to joinEdge,
                    "avg_latency_ms" to latency
                )
            )
        )
    }

    private fun writeOfiSummary(
        symbols: List<String>,
        summary: OfiKpiSummary,
        mode: String,
        telemetryPath: String?,
        manifestPath: String?,
        configs: Map<String, String>
    ) {
        RunSummaryWriter.writeSummary(
            root = findProjectRoot(),
            summary = RunSummary(
                strategy = "ofi_kukanov",
                mode = mode,
                timestampMs = System.currentTimeMillis(),
                configs = configs.filterValues { it.isNotBlank() },
                metrics = mapOf(
                    "net_pnl" to summary.netPnl,
                    "realized_pnl" to summary.realizedPnl,
                    "unrealized_pnl" to summary.unrealizedPnl,
                    "total_fees" to summary.totalFees,
                    "avg_slippage_bps" to summary.avgSlippageBps,
                    "avg_join_edge_bps" to summary.avgJoinEdgeBps,
                    "avg_adverse_bps" to summary.avgAdverseMoveBps,
                    "avg_latency_ms" to summary.avgLatencyMs,
                    "symbols" to symbols
                ),
                health = mapOf(
                    "trade_count" to summary.tradeCount,
                    "win_rate" to summary.winRate,
                    "fill_rate" to summary.fillRate,
                    "cancel_rate" to summary.cancelRate,
                    "stale_cancel_rate" to summary.staleCancelRate,
                    "take_rate" to summary.takeRate
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
