package com.example.vacuum

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
import com.example.platform.report.Telemetry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.time.Duration.Companion.milliseconds

object VacuumLiveRunner {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        Telemetry.configureFromEnv("vacuum_live")
        val source = (System.getenv("MARKETDATA_SOURCE") ?: "FUTURES").uppercase()
        val symbolsEnv = System.getenv("SYMBOLS")
        val topN = System.getenv("TOP_N")?.toIntOrNull() ?: 5
        val tickMs = System.getenv("TICK_MS")?.toLongOrNull() ?: 200L
        val depthLevels = System.getenv("DEPTH_LEVELS")?.toIntOrNull() ?: 10
        val depthSpeedMs = System.getenv("DEPTH_SPEED_MS")?.toLongOrNull() ?: 100L
        val snapshotDepth = System.getenv("SNAPSHOT_DEPTH")?.toIntOrNull() ?: 100
        val logEvery = System.getenv("LOG_EVERY_TICKS")?.toLongOrNull() ?: 1_000L
        val kpiEveryMs = System.getenv("LOG_KPI_EVERY_MS")?.toLongOrNull() ?: 60_000L

        println("Vacuum live (paper) starting...")
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

            val kpiBySymbol = symbols.associateWith { symbol ->
                VacuumKpiTracker(configFromEnv(symbol))
            }
            val manifestWriter = ExperimentManifestWriter.fromEnv()
            manifestWriter?.write(
                ExperimentManifest(
                    timestampMs = System.currentTimeMillis(),
                    strategy = "vacuum",
                    mode = "live",
                    symbols = symbols,
                    params = mapOf(
                        "MARKETDATA_SOURCE" to source,
                        "DEPTH_DROP_PCT" to (System.getenv("DEPTH_DROP_PCT") ?: ""),
                        "SPREAD_WIDEN_PCT" to (System.getenv("SPREAD_WIDEN_PCT") ?: "")
                    ).filterValues { it.isNotBlank() },
                    reportPath = null,
                    runId = System.getenv("RUN_ID"),
                    notes = System.getenv("RUN_NOTES")
                )
            )
            val accountRepo = SimAccountStateRepository()
            val orderLatencyMs = System.getenv("SIM_ORDER_LATENCY_MS")?.toLongOrNull() ?: 0L
            val queueBuffer = System.getenv("SIM_QUEUE_BUFFER")?.toDoubleOrNull() ?: 1.0
            val queueLevels = System.getenv("SIM_MAX_QUEUE_LEVELS")?.toIntOrNull() ?: 5
            val makerFeePct = System.getenv("MAKER_FEE_PCT")?.toDoubleOrNull() ?: 0.0002
            val takerFeePct = System.getenv("TAKER_FEE_PCT")?.toDoubleOrNull() ?: 0.0004
            val fillSimulator = ConservativeFillSimulator(
                queueBufferMultiplier = queueBuffer,
                maxDepthLevels = queueLevels
            )
            val gateway = SimExecutionGateway(
                accountRepo,
                inventoryStateRepository = null,
                fillSimulator = fillSimulator,
                orderLatencyMs = orderLatencyMs,
                makerFeePct = makerFeePct,
                takerFeePct = takerFeePct,
                fillListener = { fill -> kpiBySymbol[fill.symbol.value]?.onFill(fill) }
            )
            val strategies = symbols.associateWith { symbol ->
                val kpi = kpiBySymbol[symbol] ?: error("KPI missing for $symbol")
                VacuumStrategy(gateway, configFromEnv(symbol), kpi)
            }

            var ticks = 0L
            var lastKpiMs = 0L
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
                kpiBySymbol[state.symbol]?.onMarketState(
                    state.symbol,
                    state.midPrice ?: state.microPrice,
                    state.timestampMs
                )
                ticks++
                val now = state.eventTimeMs ?: state.timestampMs
                if (ticks % logEvery == 0L) {
                    println("ticks=$ticks last=${state.symbol} t=${state.timestampMs}")
                }
                if (lastKpiMs == 0L) lastKpiMs = now
                if (now - lastKpiMs >= kpiEveryMs) {
                    kpiBySymbol.forEach { (symbol, kpi) ->
                        VacuumReport.print(kpi.summary(), "VACUUM LIVE KPI $symbol")
                    }
                    lastKpiMs = now
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

    private fun configFromEnv(symbol: String): VacuumConfig {
        val base = VacuumConfig(symbol = symbol)
        return base.copy(
            depthLevels = System.getenv("DEPTH_LEVELS")?.toIntOrNull() ?: base.depthLevels,
            depthWindowMs = System.getenv("DEPTH_WINDOW_MS")?.toLongOrNull() ?: base.depthWindowMs,
            depthDropPct = System.getenv("DEPTH_DROP_PCT")?.toDoubleOrNull() ?: base.depthDropPct,
            depthRefillPct = System.getenv("DEPTH_REFILL_PCT")?.toDoubleOrNull() ?: base.depthRefillPct,
            spreadWindowMs = System.getenv("SPREAD_WINDOW_MS")?.toLongOrNull() ?: base.spreadWindowMs,
            spreadWidenPct = System.getenv("SPREAD_WIDEN_PCT")?.toDoubleOrNull() ?: base.spreadWidenPct,
            maxSpreadPct = System.getenv("MAX_SPREAD_PCT")?.toDoubleOrNull() ?: base.maxSpreadPct,
            minTradeCount1s = System.getenv("MIN_TRADE_COUNT_1S")?.toIntOrNull() ?: base.minTradeCount1s,
            minTradeImbalance1s = System.getenv("MIN_TRADE_IMB_1S")?.toDoubleOrNull()
                ?: base.minTradeImbalance1s,
            orderQty = System.getenv("ORDER_QTY")?.toDoubleOrNull() ?: base.orderQty,
            priceTick = System.getenv("PRICE_TICK")?.toDoubleOrNull() ?: base.priceTick,
            qtyStep = System.getenv("QTY_STEP")?.toDoubleOrNull() ?: base.qtyStep,
            entryCooldownMs = System.getenv("ENTRY_COOLDOWN_MS")?.toLongOrNull() ?: base.entryCooldownMs,
            orderTtlMs = System.getenv("ORDER_TTL_MS")?.toLongOrNull() ?: base.orderTtlMs,
            maxHoldMs = System.getenv("MAX_HOLD_MS")?.toLongOrNull() ?: base.maxHoldMs,
            trailingStopBps = System.getenv("TRAILING_STOP_BPS")?.toDoubleOrNull()
                ?: base.trailingStopBps,
            slippagePauseBps = System.getenv("SLIPPAGE_PAUSE_BPS")?.toDoubleOrNull()
                ?: base.slippagePauseBps,
            tailLossBps = System.getenv("TAIL_LOSS_BPS")?.toDoubleOrNull() ?: base.tailLossBps,
            maxTailLosses = System.getenv("MAX_TAIL_LOSSES")?.toIntOrNull() ?: base.maxTailLosses,
            pauseMs = System.getenv("PAUSE_MS")?.toLongOrNull() ?: base.pauseMs,
            logSignals = System.getenv("LOG_SIGNALS")?.toBooleanStrictOrNull() ?: base.logSignals
        )
    }
}
