package com.example.avellaneda

import com.example.account.domain.Price
import com.example.account.domain.Symbol
import com.example.account.impl.config.InventoryWalletConfig
import com.example.account.impl.inventory.CsvInventoryStateRepository
import com.example.account.impl.inventory.CsvWalletStore
import com.example.avellaneda.metrics.AdverseSelectionTracker
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.time.Duration.Companion.milliseconds

object AvellanedaMmLiveRunner {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val source = (System.getenv("MARKETDATA_SOURCE") ?: "FUTURES").uppercase()
        val symbolsEnv = System.getenv("SYMBOLS")
        val topN = System.getenv("TOP_N")?.toIntOrNull() ?: 50
        val tickMs = System.getenv("TICK_MS")?.toLongOrNull() ?: 200L
        val depthLevels = System.getenv("DEPTH_LEVELS")?.toIntOrNull() ?: 10
        val depthSpeedMs = System.getenv("DEPTH_SPEED_MS")?.toLongOrNull() ?: 100L
        val snapshotDepth = System.getenv("SNAPSHOT_DEPTH")?.toIntOrNull() ?: 100
        val logEvery = System.getenv("LOG_EVERY_TICKS")?.toLongOrNull() ?: 1_000L
        val pnlEveryMs = System.getenv("LOG_PNL_EVERY_MS")?.toLongOrNull() ?: 60_000L

        println("Avellaneda live (paper) starting...")
        println("Source       : $source")
        println("TopN         : $topN")
        println("TickMs       : $tickMs")
        println("DepthLevels  : $depthLevels")
        println("DepthSpeedMs : $depthSpeedMs")
        println("SnapDepth    : $snapshotDepth")
        println("LogEvery     : $logEvery")
        println("PnlEveryMs   : $pnlEveryMs")

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
            val makerFeePct = System.getenv("MAKER_FEE_PCT")?.toDoubleOrNull() ?: 0.0
            val takerFeePct = System.getenv("TAKER_FEE_PCT")?.toDoubleOrNull() ?: 0.0
            val fillSimulator = ConservativeFillSimulator(
                queueBufferMultiplier = queueBuffer,
                maxDepthLevels = queueLevels
            )
            val adverseTracker = AdverseSelectionTracker()
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
            val strategies = symbols.associateWith { symbol ->
                val base = AvellanedaMmConfig.default(symbol)
                val cfg = base.copy(
                    orderQty = System.getenv("ORDER_QTY")?.toDoubleOrNull() ?: base.orderQty,
                    minSpreadPct = System.getenv("MIN_SPREAD_PCT")?.toDoubleOrNull()
                        ?: base.minSpreadPct,
                    inventorySkew = System.getenv("INVENTORY_SKEW")?.toDoubleOrNull()
                        ?: base.inventorySkew,
                    maxInventory = System.getenv("MAX_INVENTORY")?.toDoubleOrNull()
                        ?: base.maxInventory,
                    priceTick = System.getenv("PRICE_TICK")?.toDoubleOrNull() ?: base.priceTick,
                    qtyStep = System.getenv("QTY_STEP")?.toDoubleOrNull() ?: base.qtyStep,
                    quoteRefreshMs = System.getenv("QUOTE_REFRESH_MS")?.toLongOrNull()
                        ?: base.quoteRefreshMs,
                    maxQuoteAgeMs = System.getenv("MAX_QUOTE_AGE_MS")?.toLongOrNull()
                        ?: base.maxQuoteAgeMs,
                    gateCooldownMs = System.getenv("GATE_COOLDOWN_MS")?.toLongOrNull()
                        ?: base.gateCooldownMs,
                    spreadWindowMs = System.getenv("SPREAD_WINDOW_MS")?.toLongOrNull()
                        ?: base.spreadWindowMs,
                    minAvgSpreadPct = System.getenv("MIN_AVG_SPREAD_PCT")?.toDoubleOrNull()
                        ?: base.minAvgSpreadPct,
                    maxAvgSpreadPct = System.getenv("MAX_AVG_SPREAD_PCT")?.toDoubleOrNull()
                        ?: base.maxAvgSpreadPct,
                    maxSpreadPct = System.getenv("MAX_SPREAD_PCT")?.toDoubleOrNull()
                        ?: base.maxSpreadPct,
                    minTopDepth = System.getenv("MIN_TOP_DEPTH")?.toDoubleOrNull()
                        ?: base.minTopDepth,
                    topDepthLevels = System.getenv("TOP_DEPTH_LEVELS")?.toIntOrNull()
                        ?: base.topDepthLevels,
                    maxDepthImbalance = System.getenv("MAX_DEPTH_IMBALANCE")?.toDoubleOrNull()
                        ?: base.maxDepthImbalance,
                    maxTradeImbalance1s = System.getenv("MAX_TRADE_IMBALANCE_1S")?.toDoubleOrNull()
                        ?: base.maxTradeImbalance1s,
                    minTradeCount1sForToxicity = System.getenv("MIN_TRADE_COUNT_1S")?.toIntOrNull()
                        ?: base.minTradeCount1sForToxicity,
                    maxVol1s = System.getenv("MAX_VOL_1S")?.toDoubleOrNull() ?: base.maxVol1s,
                    maxVol5s = System.getenv("MAX_VOL_5S")?.toDoubleOrNull() ?: base.maxVol5s,
                    maxVol10s = System.getenv("MAX_VOL_10S")?.toDoubleOrNull() ?: base.maxVol10s,
                    volSpreadMultiplier = System.getenv("VOL_SPREAD_MULT")?.toDoubleOrNull()
                        ?: base.volSpreadMultiplier,
                    quoteStyle = parseQuoteStyle(System.getenv("QUOTE_STYLE")),
                    logGateDecisions = System.getenv("LOG_GATES")?.toBooleanStrictOrNull()
                        ?: base.logGateDecisions
                )
                if (System.getenv("LOG_CONFIG")?.toBooleanStrictOrNull() == true) {
                    println("config[$symbol]=$cfg")
                }
                AvellanedaMmStrategy(gateway, cfg)
            }

            println("Live paper engine running. Press Ctrl+C to stop.")
            var ticks = 0L
            var lastPnlLog = 0L
            var lastHealthLog = 0L
            var lastFillTotal = 0
            val fillCounts = mutableMapOf<String, Int>()

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
                adverseTracker.onMarketState(state)
                strategies[state.symbol]?.onMarketState(state)
                inventoryRepo.applyMarkPrice(
                    Symbol.of(state.symbol),
                    Price.fromDouble(state.midPrice ?: state.lastTradePrice ?: return@collect),
                    state.eventTimeMs ?: state.timestampMs
                )
                ticks++
                val now = state.eventTimeMs ?: state.timestampMs

                val fills = accountRepo.getFills(Symbol.of(state.symbol), sinceTimeMs = null)
                val prevCount = fillCounts[state.symbol] ?: 0
                if (fills.size > prevCount) {
                    val last = fills.lastOrNull()
                    println("fills=${fills.size} lastFill=${last?.symbol?.value} price=${last?.price?.value}")
                    fillCounts[state.symbol] = fills.size
                }

                if (ticks % logEvery == 0L) {
                    println("ticks=$ticks last=${state.symbol} t=${state.timestampMs}")
                }

                if (lastPnlLog == 0L) lastPnlLog = now
                if (now - lastPnlLog >= pnlEveryMs) {
                    logPnlSummary(inventoryRepo)
                    logHealthSummary(
                        inventoryRepo,
                        fillCounts,
                        lastFillTotal,
                        lastPnlLog,
                        now,
                        adverseTracker
                    )
                    lastFillTotal = fillCounts.values.sum()
                    lastPnlLog = now
                }
            }
        } finally {
            stopKoin()
        }
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
        fillCounts: Map<String, Int>,
        lastFillTotal: Int,
        lastLogMs: Long,
        nowMs: Long,
        adverseTracker: AdverseSelectionTracker
    ) {
        val elapsedSec = ((nowMs - lastLogMs).coerceAtLeast(1L)) / 1000.0
        val totalFills = fillCounts.values.sum()
        val fillsPerMin = (totalFills - lastFillTotal) * (60.0 / elapsedSec)
        val positions = inventoryRepo.getInventory()
        val maxAbsQty =
            positions.maxOfOrNull { kotlin.math.abs(it.quantity.value.toDouble()) } ?: 0.0
        val sumAbsQty = positions.sumOf { kotlin.math.abs(it.quantity.value.toDouble()) }
        val labels = adverseTracker.horizonsLabel()
        val adv = adverseTracker.snapshotBps()
        val advStr = labels.zip(adv).joinToString(" ") { (label, value) ->
            val v = value ?: 0.0
            "adv${label}=${"%.2f".format(v)}"
        }
        println(
            String.format(
                "health: fillsPerMin=%.2f positions=%d maxAbsQty=%.6f sumAbsQty=%.6f %s",
                fillsPerMin,
                positions.size,
                maxAbsQty,
                sumAbsQty,
                advStr
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
        println("Fetching universe...")
        val config = UniverseConfig(
            quoteAssets = quoteAssets,
            minQuoteVolume = minQuoteVolume,
            minTrades = minTrades,
            maxSymbols = topN
        )
        return universe.fetchTopSymbols(config).map { it.symbol }
    }

    private fun parseQuoteStyle(raw: String?): QuoteStyle {
        return when (raw?.trim()?.uppercase()) {
            "IMPROVE" -> QuoteStyle.IMPROVE
            "WIDEN" -> QuoteStyle.WIDEN
            else -> QuoteStyle.JOIN
        }
    }
}
