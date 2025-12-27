package com.example.pairs

import com.example.execution.impl.ConservativeFillSimulator
import com.example.execution.impl.SimAccountStateRepository
import com.example.execution.impl.SimExecutionGateway
import com.example.marketdata.model.MarketStateConfig
import com.example.marketdata.model.asSymbol
import com.example.marketdata.repository.FuturesMarketStateRepository
import com.example.marketdata.repository.MarketStateRepository
import com.example.network.di.networkModule
import com.example.network.futures.di.futuresModule
import com.example.platform.model.MarketState
import com.example.platform.report.ExperimentManifest
import com.example.platform.report.ExperimentManifestWriter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.time.Duration.Companion.milliseconds

object PairsLiveRunner {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val source = (System.getenv("MARKETDATA_SOURCE") ?: "FUTURES").uppercase()
        val symbolA = System.getenv("SYMBOL_A") ?: "BTCUSDT"
        val symbolB = System.getenv("SYMBOL_B") ?: "ETHUSDT"
        val tickMs = System.getenv("TICK_MS")?.toLongOrNull() ?: 200L
        val depthLevels = System.getenv("DEPTH_LEVELS")?.toIntOrNull() ?: 10
        val depthSpeedMs = System.getenv("DEPTH_SPEED_MS")?.toLongOrNull() ?: 100L
        val snapshotDepth = System.getenv("SNAPSHOT_DEPTH")?.toIntOrNull() ?: 100
        val kpiEveryMs = System.getenv("LOG_KPI_EVERY_MS")?.toLongOrNull() ?: 60_000L

        println("Pairs live (paper) starting...")
        println("Source       : $source")
        println("Symbols      : $symbolA/$symbolB")
        println("TickMs       : $tickMs")
        println("DepthLevels  : $depthLevels")
        println("DepthSpeedMs : $depthSpeedMs")
        println("SnapDepth    : $snapshotDepth")

        val koinApp = startKoin {
            if (source == "FUTURES") {
                modules(networkModule, futuresModule)
            } else {
                modules(networkModule)
            }
        }
        val koin = koinApp.koin

        try {
            val config = MarketStateConfig(
                tick = tickMs.milliseconds,
                depthLevels = depthLevels,
                depthSpeed = depthSpeedMs.milliseconds,
                snapshotDepthLimit = snapshotDepth
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
            val configPairs = configFromEnv(symbolA, symbolB)
            val kpi = PairsKpiTracker(configPairs)
            val gateway = SimExecutionGateway(
                accountRepo,
                inventoryStateRepository = null,
                fillSimulator = fillSimulator,
                orderLatencyMs = orderLatencyMs,
                makerFeePct = makerFeePct,
                takerFeePct = takerFeePct
            )
            val strategy = PairsStrategy(gateway, configPairs, kpi)
            val manifestWriter = ExperimentManifestWriter.fromEnv()
            manifestWriter?.write(
                ExperimentManifest(
                    timestampMs = System.currentTimeMillis(),
                    strategy = "pairs",
                    mode = "live",
                    symbols = listOf(symbolA, symbolB),
                    params = mapOf(
                        "MARKETDATA_SOURCE" to source,
                        "ENTRY_Z" to (System.getenv("ENTRY_Z") ?: ""),
                        "EXIT_Z" to (System.getenv("EXIT_Z") ?: ""),
                        "WINDOW_MS" to (System.getenv("WINDOW_MS") ?: "")
                    ).filterValues { it.isNotBlank() },
                    reportPath = null,
                    runId = System.getenv("RUN_ID"),
                    notes = System.getenv("RUN_NOTES")
                )
            )

            var lastKpiMs = 0L
            val symbolList = listOf(symbolA.asSymbol(), symbolB.asSymbol())
            val flow: Flow<MarketState> = if (source == "FUTURES") {
                val repo = koin.get<FuturesMarketStateRepository>()
                repo.streamMarketState(symbolList, config)
            } else {
                val repo = koin.get<MarketStateRepository>()
                repo.streamMarketState(symbolList, config)
            }

            flow.collect { state ->
                if (state.symbol != symbolA && state.symbol != symbolB) return@collect
                gateway.onMarketState(state)
                strategy.onMarketState(state)
                val now = state.eventTimeMs ?: state.timestampMs
                if (lastKpiMs == 0L) lastKpiMs = now
                if (now - lastKpiMs >= kpiEveryMs) {
                    PairsReport.print(kpi.summary(), "PAIRS LIVE KPI")
                    lastKpiMs = now
                }
            }
        } finally {
            stopKoin()
        }
    }

    private fun configFromEnv(symbolA: String, symbolB: String): PairsConfig {
        val base = PairsConfig(symbolA = symbolA, symbolB = symbolB)
        return base.copy(
            windowMs = System.getenv("WINDOW_MS")?.toLongOrNull() ?: base.windowMs,
            minSamples = System.getenv("MIN_SAMPLES")?.toIntOrNull() ?: base.minSamples,
            entryZ = System.getenv("ENTRY_Z")?.toDoubleOrNull() ?: base.entryZ,
            exitZ = System.getenv("EXIT_Z")?.toDoubleOrNull() ?: base.exitZ,
            maxHoldMs = System.getenv("MAX_HOLD_MS")?.toLongOrNull() ?: base.maxHoldMs,
            minCorr = System.getenv("MIN_CORR")?.toDoubleOrNull() ?: base.minCorr,
            maxVol = System.getenv("MAX_VOL")?.toDoubleOrNull() ?: base.maxVol,
            trendCountLimit = System.getenv("TREND_COUNT_LIMIT")?.toIntOrNull() ?: base.trendCountLimit,
            notional = System.getenv("NOTIONAL")?.toDoubleOrNull() ?: base.notional,
            priceTick = System.getenv("PRICE_TICK")?.toDoubleOrNull() ?: base.priceTick,
            qtyStep = System.getenv("QTY_STEP")?.toDoubleOrNull() ?: base.qtyStep,
            orderTtlMs = System.getenv("ORDER_TTL_MS")?.toLongOrNull() ?: base.orderTtlMs,
            makerFeePct = System.getenv("MAKER_FEE_PCT")?.toDoubleOrNull() ?: base.makerFeePct,
            takerFeePct = System.getenv("TAKER_FEE_PCT")?.toDoubleOrNull() ?: base.takerFeePct,
            tailZ = System.getenv("TAIL_Z")?.toDoubleOrNull() ?: base.tailZ,
            logSignals = System.getenv("LOG_SIGNALS")?.toBooleanStrictOrNull() ?: base.logSignals
        )
    }
}
