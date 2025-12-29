package com.example.superbot

import com.example.account.domain.BalanceSnapshot
import com.example.execution.domain.RiskBudget
import com.example.execution.impl.ConservativeFillSimulator
import com.example.execution.impl.EdgeScoreEngine
import com.example.execution.impl.ExecutionPolicy
import com.example.execution.impl.IntentAllocator
import com.example.execution.impl.PortfolioEngine
import com.example.execution.impl.RegimeEngine
import com.example.execution.impl.RiskBudgetEnv
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
import com.example.platform.report.Telemetry
import com.example.avellaneda.AvellanedaMmConfig
import com.example.avellaneda.AvellanedaMmIntentStrategy
import com.example.ofi.kukanov.OfiKukanovIntentStrategy
import com.example.ofi.kukanov.OfiStrategyConfig
import com.example.pairs.PairsConfig
import com.example.pairs.PairsIntentStrategy
import com.example.survivor.SurvivorConfig
import com.example.survivor.SurvivorCsvTailer
import com.example.survivor.SurvivorIntentStrategy
import com.example.survivor.asMarketState
import com.example.vacuum.VacuumConfig
import com.example.vacuum.VacuumIntentStrategy
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

object Superbot {
    private val statsLock = Any()
    private val strategyStats = ConcurrentHashMap<String, StrategyStat>()
    private val symbolStats = ConcurrentHashMap<String, SymbolStat>()

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        Telemetry.configureFromEnv("superbot", defaultEnabled = true)
        val source = (System.getenv("MARKETDATA_SOURCE") ?: "FUTURES").uppercase()
        val symbolsEnv = System.getenv("SYMBOLS")
        val topN = System.getenv("TOP_N")?.toIntOrNull() ?: 5
        val tickMs = System.getenv("TICK_MS")?.toLongOrNull() ?: 200L
        val depthLevels = System.getenv("DEPTH_LEVELS")?.toIntOrNull() ?: 10
        val depthSpeedMs = System.getenv("DEPTH_SPEED_MS")?.toLongOrNull() ?: 100L
        val snapshotDepth = System.getenv("SNAPSHOT_DEPTH")?.toIntOrNull() ?: 100
        val logEveryTicks = envLong("LOG_EVERY_TICKS", 1000L)
        val logIntents = envBool("LOG_INTENTS", true)
        val logRegime = envBool("LOG_REGIME", false)
        val healthEveryMs = envLong("HEALTH_EVERY_MS", 60_000L)
        val kpiEveryMs = envLong("KPI_EVERY_MS", 60_000L)
        val emaAlpha = envDouble("STATS_EMA_ALPHA", 0.1)

        println("Superbot starting...")
        println("Source       : $source")
        println("TopN         : $topN")
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
            val universe = koin.get<BinanceUniverse>()
            val minQuoteVolume = System.getenv("MIN_QUOTE_VOLUME")?.toDoubleOrNull() ?: 0.0
            val minTrades = System.getenv("MIN_TRADES")?.toLongOrNull() ?: 0L
            val quoteAssets = System.getenv("QUOTE_ASSETS")
                ?.split(',')
                ?.map { it.trim().uppercase() }
                ?.filter { it.isNotBlank() }
                ?.toSet()
                ?: setOf("USDT")
            val symbols = resolveSymbols(symbolsEnv, topN, universe, minQuoteVolume, minTrades, quoteAssets)
            println("Symbols (${symbols.size}): ${symbols.joinToString(", ")}")
            Telemetry.emit(
                type = "config_snapshot",
                tsMs = System.currentTimeMillis(),
                data = mapOf(
                    "strategy_id" to "superbot",
                    "mode" to "live",
                    "symbols" to symbols,
                    "params" to mapOf(
                        "MARKETDATA_SOURCE" to source,
                        "TOP_N" to topN,
                        "TICK_MS" to tickMs,
                        "DEPTH_LEVELS" to depthLevels,
                        "DEPTH_SPEED_MS" to depthSpeedMs,
                        "SNAPSHOT_DEPTH" to snapshotDepth
                    )
                )
            )

            val config = MarketStateConfig(
                tick = tickMs.milliseconds,
                depthLevels = depthLevels,
                depthSpeed = depthSpeedMs.milliseconds,
                snapshotDepthLimit = snapshotDepth
            )

            val accountRepo = SimAccountStateRepository(
                initialBalances = initialBalancesFromEnv()
            )
            val gateway = SimExecutionGateway(
                accountRepo,
                fillSimulator = ConservativeFillSimulator()
            )

            val strategies = buildStrategies(symbols, logIntents)
            val allocator = IntentAllocator(
                riskBudget = riskBudgetFromEnv()
            )
            val policy = ExecutionPolicy(gateway)
            val regimeEngine = RegimeEngine.fromEnv()
            val engine = PortfolioEngine(
                gateway,
                allocator,
                policy,
                strategies = strategies,
                edgeScoreEngine = EdgeScoreEngine.fromEnv()
            )

            startSurvivorTailer(engine, logIntents)

            val symbolList = symbols.map { it.asSymbol() }
            val flow: Flow<MarketState> = if (source == "FUTURES") {
                val repo = koin.get<FuturesMarketStateRepository>()
                repo.streamMarketState(symbolList, config)
            } else {
                val repo = koin.get<MarketStateRepository>()
                repo.streamMarketState(symbolList, config)
            }

            var ticks = 0L
            var lastHealthMs = 0L
            var lastKpiMs = 0L
            val startMs = System.currentTimeMillis()
            flow.collect { state ->
                gateway.onMarketState(state)
                engine.onMarketState(state)
                ticks++
                updateSymbolStats(state, emaAlpha)
                if (logEveryTicks > 0 && ticks % logEveryTicks == 0L) {
                    val mid = state.midPrice ?: state.microPrice
                    println(
                        "tick=$ticks symbol=${state.symbol} mid=${mid ?: "NA"} " +
                            "spread=${state.spread ?: "NA"} vol1s=${state.vol1s ?: "NA"} " +
                            "imbalance=${state.tradeImbalance1s} trades1s=${state.tradeCount1s}"
                    )
                    if (logRegime) {
                        val regimes = regimeEngine.evaluate(state)
                        val summary = regimes.values.joinToString(" ") {
                            "${it.strategyId}:${if (it.enabled) "on" else "off"}:${"%.2f".format(it.scale)}" +
                                (it.reason?.let { r -> "($r)" } ?: "")
                        }
                        println("regime symbol=${state.symbol} $summary")
                    }
                }
                val now = state.eventTimeMs ?: state.timestampMs
                if (healthEveryMs > 0 && (lastHealthMs == 0L || now - lastHealthMs >= healthEveryMs)) {
                    Telemetry.emit(
                        type = "health_summary",
                        tsMs = now,
                        data = mapOf(
                            "strategy_id" to "superbot",
                            "mode" to "live",
                            "net" to 0.0,
                            "fees" to 0.0,
                            "extra" to mapOf(
                                "ticks" to ticks,
                                "last_symbol" to state.symbol,
                                "spread" to (state.spread ?: 0.0),
                                "vol1s" to (state.vol1s ?: 0.0)
                            )
                        )
                    )
                    lastHealthMs = now
                }
                if (kpiEveryMs > 0 && (lastKpiMs == 0L || now - lastKpiMs >= kpiEveryMs)) {
                    Telemetry.emit(
                        type = "kpi_snapshot",
                        tsMs = now,
                        data = buildKpiSnapshot(startMs, ticks)
                    )
                    lastKpiMs = now
                }
            }
        } finally {
            stopKoin()
        }
    }

    private fun buildStrategies(
        symbols: List<String>,
        logIntents: Boolean
    ): List<com.example.execution.domain.IntentStrategy> {
        val strategies = ArrayList<com.example.execution.domain.IntentStrategy>()
        val mmEnabled = envBool("MM_ENABLED", true)
        val ofiEnabled = envBool("OFI_ENABLED", true)
        val vacuumEnabled = envBool("VACUUM_ENABLED", true)
        if (mmEnabled) {
            for (symbol in symbols) {
                val config = mmConfig(symbol)
                strategies.add(wrapIfLogging(AvellanedaMmIntentStrategy(config = config), logIntents))
            }
        }
        if (ofiEnabled) {
            for (symbol in symbols) {
                val config = ofiConfig(symbol)
                strategies.add(wrapIfLogging(OfiKukanovIntentStrategy(config = config), logIntents))
            }
        }
        if (vacuumEnabled) {
            for (symbol in symbols) {
                val config = vacuumConfig(symbol)
                strategies.add(wrapIfLogging(VacuumIntentStrategy(config = config), logIntents))
            }
        }
        val pairs = pairsConfig(symbols)
        if (pairs != null) {
            strategies.add(wrapIfLogging(PairsIntentStrategy(config = pairs), logIntents))
        }
        return strategies
    }

    private fun startSurvivorTailer(engine: PortfolioEngine, logIntents: Boolean) {
        val path = System.getenv("SURVIVOR_TAIL_CSV") ?: return
        val pollMs = System.getenv("SURVIVOR_POLL_MS")?.toLongOrNull() ?: 1_000L
        val file = File(path)
        println("Survivor tail enabled: ${file.absolutePath}")
        val config = survivorConfig()
        val strategy = SurvivorIntentStrategy(config)
        val tailer = SurvivorCsvTailer(file, pollMs = pollMs)
        CoroutineScope(Dispatchers.IO).launch {
            tailer.stream().collect { snapshot ->
                val intents = strategy.onSnapshot(
                    snapshot,
                    com.example.execution.domain.StrategyContext(
                        positions = emptyMap(),
                        nowMs = snapshot.timestampMs
                    )
                )
                if (intents.isEmpty()) return@collect
                if (logIntents) {
                    println(
                        "intent strategy=survivor symbol=${snapshot.symbol} funding=${snapshot.fundingRate} " +
                            "basis=${snapshot.basisPct} count=${intents.size}"
                    )
                }
                updateStrategyStats("survivor", intents, snapshot.symbol, snapshot.timestampMs)
                engine.onExternalIntents(snapshot.asMarketState(), intents)
            }
        }
    }

    private fun pairsConfig(symbols: List<String>): PairsConfig? {
        val symbolA = System.getenv("PAIRS_SYMBOL_A") ?: symbols.getOrNull(0) ?: return null
        val symbolB = System.getenv("PAIRS_SYMBOL_B") ?: symbols.getOrNull(1) ?: return null
        val base = PairsConfig(symbolA = symbolA, symbolB = symbolB)
        return base.copy(
            entryZ = envDouble("PAIRS_ENTRY_Z", base.entryZ),
            exitZ = envDouble("PAIRS_EXIT_Z", base.exitZ),
            minCorr = envDouble("PAIRS_MIN_CORR", base.minCorr),
            maxVol = envDouble("PAIRS_MAX_VOL", base.maxVol),
            notional = envDouble("PAIRS_NOTIONAL", base.notional),
            orderTtlMs = envLong("PAIRS_ORDER_TTL_MS", base.orderTtlMs)
        )
    }

    private fun survivorConfig(): SurvivorConfig {
        val base = SurvivorConfig(symbol = System.getenv("SURVIVOR_SYMBOL") ?: "BTCUSDT")
        return base.copy(
            orderQty = envDouble("SURVIVOR_ORDER_QTY", base.orderQty),
            entryFundingThreshold = envDouble("ENTRY_FUNDING", base.entryFundingThreshold),
            exitFundingThreshold = envDouble("EXIT_FUNDING", base.exitFundingThreshold),
            basisStopAbsPct = envDouble("BASIS_STOP_PCT", base.basisStopAbsPct),
            maxVolatility = envDouble("SURVIVOR_MAX_VOL", base.maxVolatility),
            maxSpreadPct = envDouble("SURVIVOR_MAX_SPREAD_PCT", base.maxSpreadPct),
            maxOiJumpPct = envDouble("SURVIVOR_MAX_OI_JUMP_PCT", base.maxOiJumpPct),
            oiWindowMs = envLong("SURVIVOR_OI_WINDOW_MS", base.oiWindowMs),
            maxHoldMs = envLong("SURVIVOR_MAX_HOLD_MS", base.maxHoldMs),
            orderTtlMs = envLong("SURVIVOR_ORDER_TTL_MS", base.orderTtlMs)
        )
    }

    private fun mmConfig(symbol: String): AvellanedaMmConfig {
        val base = AvellanedaMmConfig.default(symbol)
        return base.copy(
            orderQty = envDouble("MM_ORDER_QTY", base.orderQty),
            maxInventory = envDouble("MM_MAX_INVENTORY", base.maxInventory),
            minSpreadPct = envDouble("MM_MIN_SPREAD_PCT", base.minSpreadPct),
            maxSpreadPct = envDoubleNullable("MM_MAX_SPREAD_PCT", base.maxSpreadPct),
            quoteRefreshMs = envLong("MM_QUOTE_REFRESH_MS", base.quoteRefreshMs),
            maxQuoteAgeMs = envLong("MM_MAX_QUOTE_AGE_MS", base.maxQuoteAgeMs),
            minTopDepth = envDoubleNullable("MM_MIN_TOP_DEPTH", base.minTopDepth)
        )
    }

    private fun ofiConfig(symbol: String): OfiStrategyConfig {
        val base = OfiStrategyConfig(symbol = symbol)
        return base.copy(
            orderQty = envDouble("OFI_ORDER_QTY", base.orderQty),
            entryThreshold = envDouble("OFI_ENTRY_THRESHOLD", base.entryThreshold),
            exitThreshold = envDouble("OFI_EXIT_THRESHOLD", base.exitThreshold),
            maxHoldMs = envLong("OFI_MAX_HOLD_MS", base.maxHoldMs),
            minSignalIntervalMs = envLong("OFI_MIN_SIGNAL_MS", base.minSignalIntervalMs),
            takeMinEdgeBps = envDouble("OFI_TAKE_MIN_EDGE_BPS", base.takeMinEdgeBps),
            entryEdgeMultiplier = envDouble("OFI_ENTRY_EDGE_MULT", base.entryEdgeMultiplier)
        )
    }

    private fun vacuumConfig(symbol: String): VacuumConfig {
        val base = VacuumConfig(symbol = symbol)
        return base.copy(
            orderQty = envDouble("VACUUM_ORDER_QTY", base.orderQty),
            depthDropPct = envDouble("VACUUM_DEPTH_DROP_PCT", base.depthDropPct),
            spreadWidenPct = envDouble("VACUUM_SPREAD_WIDEN_PCT", base.spreadWidenPct),
            minTradeImbalance1s = envDouble("VACUUM_MIN_TRADE_IMB_1S", base.minTradeImbalance1s),
            minTradeCount1s = envInt("VACUUM_MIN_TRADE_COUNT_1S", base.minTradeCount1s),
            orderTtlMs = envLong("VACUUM_ORDER_TTL_MS", base.orderTtlMs),
            maxHoldMs = envLong("VACUUM_MAX_HOLD_MS", base.maxHoldMs)
        )
    }

    private fun riskBudgetFromEnv(): RiskBudget {
        val shares = mapOf(
            "avellaneda_mm" to 0.30,
            "ofi_kukanov" to 0.20,
            "vacuum" to 0.15,
            "survivor" to 0.40,
            "pairs" to 0.25
        )
        return RiskBudgetEnv.fromEnv(
            defaultTotal = envDouble("RISK_BUDGET_TOTAL", 1e12),
            defaultShares = shares
        )
    }

    private fun initialBalancesFromEnv(): List<BalanceSnapshot> {
        val asset = System.getenv("SIM_BALANCE_ASSET") ?: "USDT"
        val free = envDouble("SIM_BALANCE_FREE", 100_000.0)
        val locked = envDouble("SIM_BALANCE_LOCKED", 0.0)
        return listOf(
            BalanceSnapshot(
                asset = com.example.account.domain.Asset.of(asset),
                free = com.example.account.domain.Qty.fromDouble(free),
                locked = com.example.account.domain.Qty.fromDouble(locked)
            )
        )
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
        val config = UniverseConfig(
            quoteAssets = quoteAssets,
            minQuoteVolume = minQuoteVolume,
            minTrades = minTrades,
            maxSymbols = topN
        )
        return universe.fetchTopSymbols(config).map { it.symbol }
    }

    private fun envDouble(name: String, default: Double): Double {
        return System.getenv(name)?.toDoubleOrNull() ?: default
    }

    private fun envDoubleNullable(name: String, default: Double?): Double? {
        val value = System.getenv(name)?.toDoubleOrNull()
        return value ?: default
    }

    private fun envLong(name: String, default: Long): Long {
        return System.getenv(name)?.toLongOrNull() ?: default
    }

    private fun envInt(name: String, default: Int): Int {
        return System.getenv(name)?.toIntOrNull() ?: default
    }

    private fun envBool(name: String, default: Boolean): Boolean {
        return System.getenv(name)?.toBooleanStrictOrNull() ?: default
    }

    private fun wrapIfLogging(
        strategy: com.example.execution.domain.IntentStrategy,
        enabled: Boolean
    ): com.example.execution.domain.IntentStrategy {
        if (!enabled) return strategy
        return object : com.example.execution.domain.IntentStrategy {
            override val id: String = strategy.id

            override fun onMarketState(
                state: MarketState,
                context: com.example.execution.domain.StrategyContext
            ): List<com.example.execution.domain.StrategyIntent> {
                val intents = strategy.onMarketState(state, context)
                if (intents.isNotEmpty()) {
                    val total = intents.sumOf { kotlin.math.abs(it.desiredDelta.toDouble()) }
                    val avgConf = intents.map { it.confidence }.average()
                    updateStrategyStats(id, intents, state.symbol, state.timestampMs)
                    if (avgConf > 0.0) {
                        println(
                            "intent strategy=$id symbol=${state.symbol} count=${intents.size} " +
                                "avg_conf=${"%.2f".format(avgConf)} total_delta=${"%.6f".format(total)} " +
                                "reason=${intents.firstOrNull()?.reason}"
                        )
                    }
                }
                return intents
            }
        }
    }

    private fun updateStrategyStats(
        strategyId: String,
        intents: List<com.example.execution.domain.StrategyIntent>,
        symbol: String,
        tsMs: Long
    ) {
        val filtered = intents.filter { it.confidence > 0.0 }
        if (filtered.isEmpty()) return
        val totalDelta = filtered.sumOf { kotlin.math.abs(it.desiredDelta.toDouble()) }
        val totalConf = filtered.sumOf { it.confidence }
        val count = filtered.size.toLong()
        synchronized(statsLock) {
            val stat = strategyStats.getOrPut(strategyId) { StrategyStat() }
            stat.intentCount += count
            stat.totalAbsDelta += totalDelta
            stat.totalConfidence += totalConf
            stat.lastReason = filtered.firstOrNull()?.reason
            stat.lastSymbol = symbol
            stat.lastIntentMs = tsMs
        }
    }

    private fun updateSymbolStats(state: MarketState, alpha: Double) {
        val spread = state.spread ?: return
        val vol1s = state.vol1s ?: 0.0
        val mid = state.midPrice ?: state.microPrice ?: 0.0
        synchronized(statsLock) {
            val stat = symbolStats.getOrPut(state.symbol) { SymbolStat() }
            stat.tickCount += 1
            stat.lastMid = mid
            stat.lastTs = state.timestampMs
            stat.spreadEma = ema(stat.spreadEma, spread, alpha)
            stat.vol1sEma = ema(stat.vol1sEma, vol1s, alpha)
        }
    }

    private fun buildKpiSnapshot(startMs: Long, ticks: Long): Map<String, Any?> {
        val now = System.currentTimeMillis()
        val elapsedSec = ((now - startMs).coerceAtLeast(1L)) / 1000.0
        synchronized(statsLock) {
            val strategies = strategyStats.mapValues { (_, stat) ->
                val avgConf = if (stat.intentCount > 0) stat.totalConfidence / stat.intentCount else 0.0
                val avgDelta = if (stat.intentCount > 0) stat.totalAbsDelta / stat.intentCount else 0.0
                mapOf(
                    "intents" to stat.intentCount,
                    "avg_conf" to avgConf,
                    "avg_abs_delta" to avgDelta,
                    "intents_per_min" to (stat.intentCount / (elapsedSec / 60.0)),
                    "last_symbol" to stat.lastSymbol,
                    "last_reason" to stat.lastReason,
                    "last_ts_ms" to stat.lastIntentMs
                )
            }
            val symbols = symbolStats.mapValues { (_, stat) ->
                mapOf(
                    "ticks" to stat.tickCount,
                    "last_mid" to stat.lastMid,
                    "spread_ema" to stat.spreadEma,
                    "vol1s_ema" to stat.vol1sEma,
                    "last_ts_ms" to stat.lastTs
                )
            }
            return mapOf(
                "strategy_id" to "superbot",
                "mode" to "live",
                "elapsed_sec" to elapsedSec,
                "ticks" to ticks,
                "strategies" to strategies,
                "symbols" to symbols
            )
        }
    }

    private fun ema(prev: Double, value: Double, alpha: Double): Double {
        if (prev == 0.0) return value
        val a = alpha.coerceIn(0.001, 1.0)
        return prev + a * (value - prev)
    }

    private class StrategyStat {
        var intentCount: Long = 0L
        var totalAbsDelta: Double = 0.0
        var totalConfidence: Double = 0.0
        var lastSymbol: String? = null
        var lastReason: String? = null
        var lastIntentMs: Long? = null
    }

    private class SymbolStat {
        var tickCount: Long = 0L
        var lastMid: Double = 0.0
        var spreadEma: Double = 0.0
        var vol1sEma: Double = 0.0
        var lastTs: Long = 0L
    }
}
