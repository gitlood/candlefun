package com.example.survivor

import com.example.execution.domain.ExecutionGateway
import com.example.marketdata.model.MarketStateConfig
import com.example.marketdata.model.asSymbol
import com.example.marketdata.repository.FuturesMarketStateRepository
import com.example.network.di.networkModule
import com.example.network.futures.di.futuresModule
import com.example.network.futures.interfaces.BinanceFuturesTestNetApiService
import com.example.network.futures.interfaces.FuturesMarketDataService
import com.example.platform.model.MarketState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import kotlin.time.Duration.Companion.milliseconds
import com.example.account.impl.di.accountImplModule
import com.example.execution.impl.di.executionImplModule
import java.io.File

object SurvivorTestnetRunner {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val symbol = System.getenv("SYMBOL") ?: "BTCUSDT"
        val tickMs = System.getenv("TICK_MS")?.toLongOrNull() ?: 1_000L
        val depthLevels = System.getenv("DEPTH_LEVELS")?.toIntOrNull() ?: 5
        val depthSpeedMs = System.getenv("DEPTH_SPEED_MS")?.toLongOrNull() ?: 250L
        val snapshotDepth = System.getenv("SNAPSHOT_DEPTH")?.toIntOrNull() ?: 50
        val pollMs = System.getenv("FUNDING_POLL_MS")?.toLongOrNull() ?: 5_000L
        val positionPollMs = System.getenv("POSITION_POLL_MS")?.toLongOrNull() ?: 5_000L
        val logEveryMs = System.getenv("LOG_KPI_EVERY_MS")?.toLongOrNull() ?: 60_000L
        val recordEnabled = System.getenv("RECORD_SURVIVOR_CSV")?.toBooleanStrictOrNull() ?: false
        val recordEveryMs = System.getenv("RECORD_EVERY_MS")?.toLongOrNull() ?: 1_000L
        val recordPath = System.getenv("SURVIVOR_RECORD_PATH") ?: defaultRecordPath()
        val leverage = System.getenv("LEVERAGE")?.toIntOrNull() ?: 1

        println("Survivor testnet starting...")
        println("Symbol       : $symbol")
        println("TickMs       : $tickMs")
        println("DepthLevels  : $depthLevels")
        println("DepthSpeedMs : $depthSpeedMs")
        println("SnapDepth    : $snapshotDepth")
        println("PollMs       : $pollMs")
        println("PosPollMs    : $positionPollMs")
        println("Leverage     : $leverage")
        if (recordEnabled) {
            println("RecordCsv    : $recordPath everyMs=$recordEveryMs")
        }

        val koinApp = startKoin {
            modules(networkModule, futuresModule, accountImplModule, executionImplModule)
        }
        val koin = koinApp.koin

        try {
            val api = koin.get<BinanceFuturesTestNetApiService>(named("futuresTestnetApi"))
            try {
                api.setLeverage(symbol, leverage)
            } catch (e: Exception) {
                println("Leverage set failed for $symbol: ${e.message}")
            }

            val repo = koin.get<FuturesMarketStateRepository>()
            val marketData = koin.get<FuturesMarketDataService>()
            val gateway = koin.get<ExecutionGateway>(named("futuresExecution"))

            val config = MarketStateConfig(
                tick = tickMs.milliseconds,
                depthLevels = depthLevels,
                depthSpeed = depthSpeedMs.milliseconds,
                snapshotDepthLimit = snapshotDepth
            )

            val survivorConfig = configFromEnv(symbol)
            val kpi = SurvivorKpiTracker(survivorConfig)
            val gates = SurvivorGateStats()
            val strategy = SurvivorStrategy(gateway, survivorConfig, kpi, gates)
            val recorder = if (recordEnabled) {
                SurvivorCsvRecorder(File(recordPath), recordEveryMs)
            } else {
                null
            }

            val dataMutex = Mutex()
            var latestPremium: PremiumSnapshot? = null

            launch {
                while (true) {
                    try {
                        val premium = marketData.getPremiumIndex(symbol)
                        val oi = marketData.getOpenInterest(symbol)
                        val snap = PremiumSnapshot(
                            fundingRate = premium.lastFundingRate.toDoubleOrNull() ?: 0.0,
                            nextFundingTimeMs = premium.nextFundingTime,
                            markPrice = premium.markPrice.toDoubleOrNull() ?: 0.0,
                            indexPrice = premium.indexPrice.toDoubleOrNull() ?: 0.0,
                            openInterest = oi.openInterest.toDoubleOrNull() ?: 0.0
                        )
                        dataMutex.withLock { latestPremium = snap }
                    } catch (e: Exception) {
                        println("Premium/OI poll failed: ${e.message}")
                    }
                    delay(pollMs)
                }
            }

            var lastKpiMs = 0L
            var lastPosPollMs = 0L
            val flow: Flow<MarketState> = repo.streamMarketState(listOf(symbol.asSymbol()), config)
            flow.collect { state ->
                val premium = dataMutex.withLock { latestPremium }
                if (premium == null) return@collect
                val snapshot = buildSnapshot(symbol, state, premium)
                strategy.onSnapshot(snapshot)
                recorder?.record(snapshot)

                val now = snapshot.timestampMs
                if (now - lastPosPollMs >= positionPollMs) {
                    val pos = gateway.getPositions().firstOrNull { it.symbol.value == symbol }
                    if (pos != null) {
                        kpi.onPositionUpdate(symbol, pos.quantity.value.toDouble(), pos.averagePrice.value.toDouble())
                    }
                    lastPosPollMs = now
                }
                if (lastKpiMs == 0L) lastKpiMs = now
                if (now - lastKpiMs >= logEveryMs) {
                    SurvivorReport.print(kpi.summary(), "SURVIVOR TESTNET KPI")
                    println(gates.report())
                    lastKpiMs = now
                }
            }
        } finally {
            stopKoin()
        }
    }

    private fun buildSnapshot(
        symbol: String,
        state: MarketState,
        premium: PremiumSnapshot
    ): SurvivorSnapshot {
        val mid = state.midPrice ?: state.microPrice
        val spread = state.spread
        val spreadPct = if (spread != null && mid != null && mid > 0.0) {
            spread / mid
        } else {
            0.0
        }
        val vol = state.vol1s ?: state.vol5s ?: 0.0
        val ts = state.eventTimeMs ?: state.timestampMs
        return SurvivorSnapshot(
            symbol = symbol,
            timestampMs = ts,
            fundingRate = premium.fundingRate,
            nextFundingTimeMs = premium.nextFundingTimeMs,
            markPrice = premium.markPrice,
            indexPrice = premium.indexPrice,
            spreadPct = spreadPct,
            volatility = vol,
            openInterest = premium.openInterest
        )
    }

    private fun configFromEnv(symbol: String): SurvivorConfig {
        val base = SurvivorConfig(symbol = symbol)
        return base.copy(
            orderQty = System.getenv("ORDER_QTY")?.toDoubleOrNull() ?: base.orderQty,
            entryFundingThreshold = System.getenv("ENTRY_FUNDING")?.toDoubleOrNull()
                ?: base.entryFundingThreshold,
            exitFundingThreshold = System.getenv("EXIT_FUNDING")?.toDoubleOrNull()
                ?: base.exitFundingThreshold,
            basisStopAbsPct = System.getenv("BASIS_STOP_PCT")?.toDoubleOrNull()
                ?: base.basisStopAbsPct,
            maxVolatility = System.getenv("MAX_VOL")?.toDoubleOrNull() ?: base.maxVolatility,
            maxSpreadPct = System.getenv("MAX_SPREAD_PCT")?.toDoubleOrNull() ?: base.maxSpreadPct,
            maxOiJumpPct = System.getenv("MAX_OI_JUMP_PCT")?.toDoubleOrNull() ?: base.maxOiJumpPct,
            oiWindowMs = System.getenv("OI_WINDOW_MS")?.toLongOrNull() ?: base.oiWindowMs,
            maxHoldMs = System.getenv("MAX_HOLD_MS")?.toLongOrNull() ?: base.maxHoldMs,
            orderTtlMs = System.getenv("ORDER_TTL_MS")?.toLongOrNull() ?: base.orderTtlMs,
            makerFeePct = System.getenv("MAKER_FEE_PCT")?.toDoubleOrNull() ?: base.makerFeePct,
            takerFeePct = System.getenv("TAKER_FEE_PCT")?.toDoubleOrNull() ?: base.takerFeePct,
            borrowFeePctPerDay = System.getenv("BORROW_FEE_PCT_DAY")?.toDoubleOrNull()
                ?: base.borrowFeePctPerDay,
            allowHedge = System.getenv("ALLOW_HEDGE")?.toBooleanStrictOrNull() ?: base.allowHedge,
            logSignals = System.getenv("LOG_SIGNALS")?.toBooleanStrictOrNull() ?: base.logSignals
        )
    }

    private data class PremiumSnapshot(
        val fundingRate: Double,
        val nextFundingTimeMs: Long,
        val markPrice: Double,
        val indexPrice: Double,
        val openInterest: Double
    )

    private fun defaultRecordPath(): String {
        val root = findProjectRoot()
        return File(root, "survivor.csv").absolutePath
    }

    private fun findProjectRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        while (true) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            val parent = dir.parentFile ?: return dir
            dir = parent
        }
    }
}
