package com.example.ofi.kukanov

import com.example.account.domain.Price
import com.example.account.domain.Symbol
import com.example.account.impl.config.InventoryWalletConfig
import com.example.account.impl.inventory.CsvInventoryStateRepository
import com.example.account.impl.inventory.CsvWalletStore
import com.example.execution.impl.ConservativeFillSimulator
import com.example.execution.impl.SimAccountStateRepository
import com.example.execution.impl.SimExecutionGateway
import com.example.marketdata.model.MarketStateConfig
import com.example.marketdata.model.asSymbol
import com.example.marketdata.repository.FuturesMarketStateRepository
import com.example.marketdata.repository.MarketStateRepository
import com.example.network.BinanceUniverse
import com.example.network.di.networkModule
import com.example.network.futures.di.futuresModule
import com.example.platform.model.MarketState
import com.example.platform.model.UniverseConfig
import com.example.platform.report.ExperimentManifest
import com.example.platform.report.ExperimentManifestWriter
import com.example.platform.report.HealthSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.time.Duration.Companion.milliseconds

object OfiLiveRunner {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val source = (System.getenv("MARKETDATA_SOURCE") ?: "FUTURES").uppercase()
        val symbolsEnv = System.getenv("SYMBOLS")
        val topN = System.getenv("TOP_N")?.toIntOrNull() ?: 25
        val tickMs = System.getenv("TICK_MS")?.toLongOrNull() ?: 200L
        val depthLevels = System.getenv("DEPTH_LEVELS")?.toIntOrNull() ?: 10
        val depthSpeedMs = System.getenv("DEPTH_SPEED_MS")?.toLongOrNull() ?: 100L
        val snapshotDepth = System.getenv("SNAPSHOT_DEPTH")?.toIntOrNull() ?: 100
        val logEvery = System.getenv("LOG_EVERY_TICKS")?.toLongOrNull() ?: 1_000L
        val kpiEveryMs = System.getenv("LOG_KPI_EVERY_MS")?.toLongOrNull() ?: 60_000L

        println("OFI live (paper) starting...")
        println("Source       : $source")
        println("TopN         : $topN")
        println("TickMs       : $tickMs")
        println("DepthLevels  : $depthLevels")
        println("DepthSpeedMs : $depthSpeedMs")
        println("SnapDepth    : $snapshotDepth")
        println("LogEvery     : $logEvery")

        val koinApp = startKoin {
            if (source == "FUTURES") {
                modules(networkModule, futuresModule)
            } else {
                modules(networkModule)
            }
        }
        val koin = koinApp.koin

        try {
            val universe = koin.get<BinanceUniverse>()
            val minQuoteVolume = System.getenv("MIN_QUOTE_VOLUME")?.toDoubleOrNull() ?: 0.0
            val minTrades = System.getenv("MIN_TRADES")?.toLongOrNull() ?: 0L
            val quoteAssets = System.getenv("QUOTE_ASSETS")
                ?.split(',')
                ?.map { it.trim().uppercase() }
                ?.filter { it.isNotBlank() }
                ?.toSet()
                ?: setOf("USDT")
            val symbols =
                resolveSymbols(symbolsEnv, topN, universe, minQuoteVolume, minTrades, quoteAssets)
            println("Symbols (${symbols.size}): ${symbols.joinToString(", ")}")

            val config = MarketStateConfig(
                tick = tickMs.milliseconds,
                depthLevels = depthLevels,
                depthSpeed = depthSpeedMs.milliseconds,
                snapshotDepthLimit = snapshotDepth
            )

            val accountRepo = SimAccountStateRepository()
            val walletConfig = InventoryWalletConfig.default()
            val inventoryRepo = CsvInventoryStateRepository(
                CsvWalletStore(walletConfig.walletCsvPath),
                walletConfig
            )
            val orderLatencyMs = System.getenv("SIM_ORDER_LATENCY_MS")?.toLongOrNull() ?: 0L
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

            val ofiConfig = kukanovConfigFromEnv()
            val strategies = symbols.associateWith { symbol ->
                OfiKukanovStrategy(
                    gateway,
                    config = strategyConfigFromEnv(symbol),
                    signalConfig = ofiConfig,
                    kpiSink = kpiTracker
                )
            }
            val manifestWriter = ExperimentManifestWriter.fromEnv()
            manifestWriter?.write(
                ExperimentManifest(
                    timestampMs = System.currentTimeMillis(),
                    strategy = "ofi-kukanov",
                    mode = "live",
                    symbols = symbols,
                    params = mapOf(
                        "MARKETDATA_SOURCE" to source,
                        "OFI_WINDOW_MS" to (System.getenv("OFI_WINDOW_MS") ?: ""),
                        "OFI_ENTRY_THRESHOLD" to (System.getenv("OFI_ENTRY_THRESHOLD") ?: ""),
                        "OFI_EXIT_THRESHOLD" to (System.getenv("OFI_EXIT_THRESHOLD") ?: ""),
                        "ORDER_STYLE" to (System.getenv("ORDER_STYLE") ?: "")
                    ).filterValues { it.isNotBlank() },
                    reportPath = null,
                    runId = System.getenv("RUN_ID"),
                    notes = System.getenv("RUN_NOTES")
                )
            )

            println("Live paper engine running. Press Ctrl+C to stop.")
            var ticks = 0L
            var lastKpiLogMs = 0L
            val symbolList = symbols.map { it.asSymbol() }
            val flow: Flow<MarketState> = if (source == "FUTURES") {
                val repo = koin.get<FuturesMarketStateRepository>()
                repo.streamMarketState(symbolList, config)
            } else {
                val repo = koin.get<MarketStateRepository>()
                repo.streamMarketState(symbolList, config)
            }

            flow.collect { state ->
                gateway.onMarketState(state)
                strategies[state.symbol]?.onMarketState(state)
                kpiTracker.onMarketState(
                    state.symbol,
                    state.midPrice ?: state.lastTradePrice,
                    state.eventTimeMs ?: state.timestampMs
                )
                inventoryRepo.applyMarkPrice(
                    Symbol.of(state.symbol),
                    Price.fromDouble(state.midPrice ?: state.lastTradePrice ?: return@collect),
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
        } finally {
            stopKoin()
        }
    }

    private suspend fun resolveSymbols(
        symbolsEnv: String?,
        topN: Int,
        universe: BinanceUniverse,
        minQuoteVolume: Double,
        minTrades: Long,
        quoteAssets: Set<String>
    ): List<String> {
        if (!symbolsEnv.isNullOrBlank()) {
            return symbolsEnv.split(',')
                .map { it.trim().uppercase() }
                .filter { it.isNotBlank() }
        }
        println("Fetching universe...")
        val config = UniverseConfig(
            quoteAssets = quoteAssets,
            minQuoteVolume = minQuoteVolume,
            minTrades = minTrades,
            maxSymbols = topN
        )
        return universe.fetchTopSymbols(config).map { it.symbol }
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
                mode = "live",
                net = summary.netPnl,
                fees = summary.totalFees,
                adverseBps = summary.avgAdverseMoveBps,
                fills = summary.tradeCount,
                exposure = null,
                extra = mapOf(
                    "win_rate" to winRate,
                    "fill_rate" to fillRate
                )
            )
        )
    }
}
