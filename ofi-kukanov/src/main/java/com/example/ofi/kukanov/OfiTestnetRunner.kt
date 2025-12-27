package com.example.ofi.kukanov

import com.example.account.domain.Price
import com.example.account.domain.Qty
import com.example.account.domain.Symbol
import com.example.account.domain.inventory.InventoryFill
import com.example.account.domain.inventory.InventoryStateRepository
import com.example.account.impl.config.InventoryWalletConfig
import com.example.account.impl.inventory.CsvInventoryStateRepository
import com.example.account.impl.inventory.CsvWalletStore
import com.example.execution.domain.ExecutionGateway
import com.example.execution.impl.EnvExecutionCredentialsProvider
import com.example.execution.impl.FillRecord
import com.example.execution.impl.di.executionImplModule
import com.example.marketdata.model.MarketStateConfig
import com.example.marketdata.model.asSymbol
import com.example.marketdata.repository.FuturesMarketStateRepository
import com.example.network.BinanceUniverse
import com.example.network.di.networkModule
import com.example.network.futures.di.futuresModule
import com.example.network.futures.interfaces.BinanceFuturesTestNetApiService
import com.example.network.futures.interfaces.FuturesExchangeInfoService
import com.example.platform.model.MarketState
import com.example.platform.model.UniverseConfig
import com.example.platform.model.enums.OrderSide
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import kotlin.time.Duration.Companion.milliseconds

object OfiTestnetRunner {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val credsProvider = EnvExecutionCredentialsProvider()
        try {
            credsProvider.testnet()
        } catch (e: IllegalArgumentException) {
            println("Missing testnet credentials: ${e.message}")
            println("Set BINANCE_TESTNET_API_KEY / BINANCE_TESTNET_SECRET_KEY (or BINANCE_TEST_KEY / BINANCE_TEST_SECRET).")
            return@runBlocking
        }

        val source = (System.getenv("MARKETDATA_SOURCE") ?: "FUTURES").uppercase()
        if (source != "FUTURES") {
            println("OFI testnet runner currently supports FUTURES only.")
            return@runBlocking
        }
        val symbolsEnv = System.getenv("SYMBOLS")
        val topN = System.getenv("TOP_N")?.toIntOrNull() ?: 25
        val tickMs = System.getenv("TICK_MS")?.toLongOrNull() ?: 200L
        val depthLevels = System.getenv("DEPTH_LEVELS")?.toIntOrNull() ?: 10
        val depthSpeedMs = System.getenv("DEPTH_SPEED_MS")?.toLongOrNull() ?: 100L
        val snapshotDepth = System.getenv("SNAPSHOT_DEPTH")?.toIntOrNull() ?: 100
        val logEvery = System.getenv("LOG_EVERY_TICKS")?.toLongOrNull() ?: 1_000L
        val kpiEveryMs = System.getenv("LOG_KPI_EVERY_MS")?.toLongOrNull() ?: 60_000L
        val pnlEveryMs = System.getenv("LOG_PNL_EVERY_MS")?.toLongOrNull() ?: 60_000L
        val fillPollMs = System.getenv("FILL_POLL_MS")?.toLongOrNull() ?: 2_000L
        val leverage = System.getenv("LEVERAGE")?.toIntOrNull()

        println("OFI live (testnet execution) starting...")
        println("Source       : $source")
        println("TopN         : $topN")
        println("TickMs       : $tickMs")
        println("DepthLevels  : $depthLevels")
        println("DepthSpeedMs : $depthSpeedMs")
        println("SnapDepth    : $snapshotDepth")
        println("LogEvery     : $logEvery")

        val koinApp = startKoin {
            modules(networkModule, futuresModule, executionImplModule)
        }
        val koin = koinApp.koin

        try {
            val exchangeInfo = koin.get<FuturesExchangeInfoService>()
            val filters = exchangeInfo.fetchSymbolFilters()
            if (filters.isEmpty()) {
                println("No futures symbol filters available; aborting.")
                return@runBlocking
            }

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
                .filter { filters.containsKey(it) }

            val api = koin.get<BinanceFuturesTestNetApiService>()
            val liveSymbols = if (leverage == null) {
                symbols
            } else {
                val ok = mutableListOf<String>()
                for (symbol in symbols) {
                    try {
                        api.setLeverage(symbol, leverage)
                        ok.add(symbol)
                    } catch (e: Exception) {
                        println("Leverage set failed for $symbol: ${e.message}")
                    }
                }
                ok
            }

            if (liveSymbols.isEmpty()) {
                println("No symbols available after futures filtering/leverage; aborting.")
                return@runBlocking
            }
            println("Symbols (${liveSymbols.size}): ${liveSymbols.joinToString(", ")}")

            val config = MarketStateConfig(
                tick = tickMs.milliseconds,
                depthLevels = depthLevels,
                depthSpeed = depthSpeedMs.milliseconds,
                snapshotDepthLimit = snapshotDepth
            )

            val walletConfig = InventoryWalletConfig.default().let { cfg ->
                val autoPersist = System.getenv("WALLET_AUTOPERSIST")?.toBooleanStrictOrNull()
                if (autoPersist == null) cfg else cfg.copy(autoPersist = autoPersist)
            }
            val inventoryRepo = CsvInventoryStateRepository(
                CsvWalletStore(walletConfig.walletCsvPath),
                walletConfig
            )

            val makerFeePct = System.getenv("MAKER_FEE_PCT")?.toDoubleOrNull() ?: 0.0002
            val takerFeePct = System.getenv("TAKER_FEE_PCT")?.toDoubleOrNull() ?: 0.0004
            val kpiTracker = OfiKpiTracker(makerFeePct, takerFeePct)
            val adverseTracker = AdverseSelectionTrackerLite()

            val baseGateway = koin.get<ExecutionGateway>(named("futuresExecution"))
            val gateway = InventoryPositionGateway(baseGateway, inventoryRepo)

            val ofiConfig = kukanovConfigFromEnv()
            val strategies = liveSymbols.associateWith { symbol ->
                OfiKukanovStrategy(
                    gateway,
                    config = strategyConfigFromEnv(symbol),
                    signalConfig = ofiConfig,
                    kpiSink = kpiTracker
                )
            }

            println("Testnet execution running. Press Ctrl+C to stop.")
            var ticks = 0L
            var lastKpiLogMs = 0L
            var lastPnlLogMs = 0L
            var lastFillPoll = 0L
            var lastFillTotal = 0
            val fillCounts = mutableMapOf<String, Int>()
            val lastFillTime = mutableMapOf<String, Long>()
            val symbolList = liveSymbols.map { it.asSymbol() }

            val flow: Flow<MarketState> = koin.get<FuturesMarketStateRepository>()
                .streamMarketState(symbolList, config)

            flow.collect { state ->
                strategies[state.symbol]?.onMarketState(state)
                kpiTracker.onMarketState(
                    state.symbol,
                    state.midPrice ?: state.lastTradePrice,
                    state.eventTimeMs ?: state.timestampMs
                )
                adverseTracker.onMarketState(state)
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
                if (lastPnlLogMs == 0L) lastPnlLogMs = now
                if (now - lastPnlLogMs >= pnlEveryMs) {
                    val allowed = liveSymbols.toSet()
                    logPnlSummary(inventoryRepo, allowed)
                    logHealthSummary(
                        inventoryRepo,
                        allowed,
                        fillCounts,
                        lastFillTotal,
                        lastPnlLogMs,
                        now,
                        adverseTracker
                    )
                    lastFillTotal = fillCounts.values.sum()
                    lastPnlLogMs = now
                }
                if (now - lastFillPoll >= fillPollMs) {
                    pollFillsFutures(
                        liveSymbols,
                        api,
                        inventoryRepo,
                        kpiTracker,
                        adverseTracker,
                        fillCounts,
                        lastFillTime
                    )
                    lastFillPoll = now
                }
            }
        } finally {
            stopKoin()
        }
    }

    private suspend fun pollFillsFutures(
        symbols: List<String>,
        api: BinanceFuturesTestNetApiService,
        inventoryRepo: InventoryStateRepository,
        kpiTracker: OfiKpiTracker,
        adverseTracker: AdverseSelectionTrackerLite,
        fillCounts: MutableMap<String, Int>,
        lastFillTime: MutableMap<String, Long>
    ) {
        for (symbol in symbols) {
            try {
                val startTime = lastFillTime[symbol]?.plus(1)
                val trades = api.getUserTrades(symbol, startTime = startTime)
                if (trades.isNotEmpty()) {
                    val prev = fillCounts[symbol] ?: 0
                    fillCounts[symbol] = prev + trades.size
                }
                trades.forEach { t ->
                    val side = if (t.buyer) OrderSide.BUY else OrderSide.SELL
                    val qty = Qty.fromString(t.quantity)
                    val signedQty = if (t.buyer) qty else -qty
                    val price = Price.fromString(t.price)
                    inventoryRepo.applyFill(
                        InventoryFill(
                            symbol = Symbol.of(t.symbol),
                            signedQty = signedQty,
                            price = price,
                            timestampMs = t.time
                        )
                    )
                    kpiTracker.onFill(
                        FillRecord(
                            orderId = t.orderId,
                            symbol = Symbol.of(t.symbol),
                            side = side,
                            price = price,
                            quantity = qty,
                            fillTimeMs = t.time
                        )
                    )
                    adverseTracker.recordFill(t.symbol, side, price.value.toDouble(), t.time)
                }
                val maxTime = trades.maxOfOrNull { it.time }
                if (maxTime != null) lastFillTime[symbol] = maxTime
            } catch (_: Exception) {
                // Ignore invalid symbol or permission errors during polling.
            }
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
            minSignalIntervalMs = System.getenv("OFI_MIN_SIGNAL_MS")?.toLongOrNull()
                ?: base.minSignalIntervalMs,
            orderTtlMs = System.getenv("ORDER_TTL_MS")?.toLongOrNull() ?: base.orderTtlMs,
            takeOrderTtlMs = System.getenv("TAKE_ORDER_TTL_MS")?.toLongOrNull()
                ?: base.takeOrderTtlMs,
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
            "kpi net=${"%.4f".format(summary.netPnl)} " +
                "realized=${"%.4f".format(summary.realizedPnl)} " +
                "unrealized=${"%.4f".format(summary.unrealizedPnl)} " +
                "fees=${"%.4f".format(summary.totalFees)} " +
                "winRate=$winRate fillRate=$fillRate cancelRate=$cancelRate " +
                "staleRate=$staleRate takeRate=$takeRate " +
                "slipBps=$slip joinEdgeBps=$joinEdge advBps=$adverse latencyMs=$latency"
        )
    }

    private suspend fun logPnlSummary(
        inventoryRepo: InventoryStateRepository,
        allowedSymbols: Set<String>
    ) {
        val positions = inventoryRepo.getInventory().filter { allowedSymbols.contains(it.symbol.value) }
        if (positions.isEmpty()) {
            println("pnl: no positions")
            return
        }
        val header = String.format(
            Locale.US,
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
                    Locale.US,
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
                Locale.US,
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
        inventoryRepo: InventoryStateRepository,
        allowedSymbols: Set<String>,
        fillCounts: Map<String, Int>,
        lastFillTotal: Int,
        lastLogMs: Long,
        nowMs: Long,
        adverseTracker: AdverseSelectionTrackerLite
    ) {
        val elapsedSec = ((nowMs - lastLogMs).coerceAtLeast(1L)) / 1000.0
        val totalFills = fillCounts.values.sum()
        val fillsPerMin = (totalFills - lastFillTotal) * (60.0 / elapsedSec)
        val positions = inventoryRepo.getInventory().filter { allowedSymbols.contains(it.symbol.value) }
        val maxAbsQty =
            positions.maxOfOrNull { kotlin.math.abs(it.quantity.value.toDouble()) } ?: 0.0
        val sumAbsQty = positions.sumOf { kotlin.math.abs(it.quantity.value.toDouble()) }
        val labels = adverseTracker.horizonsLabel()
        val advStr = positions.sortedBy { it.symbol.value }.joinToString(" ") { p ->
            val adv = adverseTracker.snapshotBps(p.symbol.value)
            val parts = labels.zip(adv).joinToString(",") { (label, value) ->
                val v = value?.let { "%.2f".format(Locale.US, it) } ?: "NA"
                "adv${label}=$v"
            }
            "${p.symbol.value}[$parts]"
        }
        println(
            String.format(
                Locale.US,
                "health: fillsPerMin=%.2f positions=%d maxAbsQty=%.6f sumAbsQty=%.6f",
                fillsPerMin,
                positions.size,
                maxAbsQty,
                sumAbsQty
            )
        )
        println("adv: $advStr")
    }

    private class AdverseSelectionTrackerLite(
        private val horizonsMs: LongArray = longArrayOf(1_000L, 5_000L)
    ) {
        private val pending = ArrayDeque<PendingFill>()
        private val sumsBySymbol = mutableMapOf<String, DoubleArray>()
        private val countsBySymbol = mutableMapOf<String, IntArray>()

        fun recordFill(symbol: String, side: OrderSide, price: Double, timestampMs: Long) {
            pending.addLast(PendingFill(symbol, side, price, timestampMs, BooleanArray(horizonsMs.size)))
        }

        fun onMarketState(state: MarketState) {
            val mid = state.midPrice ?: state.lastTradePrice ?: return
            val now = state.eventTimeMs ?: state.timestampMs
            if (pending.isEmpty()) return

            val iter = pending.iterator()
            while (iter.hasNext()) {
                val fill = iter.next()
                if (fill.symbol != state.symbol) continue
                val sums = sumsFor(fill.symbol)
                val counts = countsFor(fill.symbol)
                var doneCount = 0
                for (i in horizonsMs.indices) {
                    if (fill.done[i]) {
                        doneCount++
                        continue
                    }
                    if (now - fill.timestampMs >= horizonsMs[i]) {
                        val adverse = adverseMove(fill.side, fill.price, mid)
                        sums[i] += adverse
                        counts[i] += 1
                        fill.done[i] = true
                        doneCount++
                    }
                }
                if (doneCount == horizonsMs.size) {
                    iter.remove()
                }
            }
        }

        fun snapshotBps(symbol: String): List<Double?> {
            val sums = sumsBySymbol[symbol] ?: return horizonsMs.map { null }
            val counts = countsBySymbol[symbol] ?: return horizonsMs.map { null }
            return horizonsMs.indices.map { i ->
                if (counts[i] == 0) null else (sums[i] / counts[i]) * 10_000.0
            }
        }

        fun horizonsLabel(): List<String> = horizonsMs.map { "${it / 1000}s" }

        private fun adverseMove(side: OrderSide, fillPrice: Double, midAfter: Double): Double {
            return when (side) {
                OrderSide.BUY -> (fillPrice - midAfter) / fillPrice
                OrderSide.SELL -> (midAfter - fillPrice) / fillPrice
            }
        }

        private data class PendingFill(
            val symbol: String,
            val side: OrderSide,
            val price: Double,
            val timestampMs: Long,
            val done: BooleanArray
        )

        private fun sumsFor(symbol: String): DoubleArray {
            return sumsBySymbol.getOrPut(symbol) { DoubleArray(horizonsMs.size) }
        }

        private fun countsFor(symbol: String): IntArray {
            return countsBySymbol.getOrPut(symbol) { IntArray(horizonsMs.size) }
        }
    }

    private class InventoryPositionGateway(
        private val delegate: ExecutionGateway,
        private val inventory: InventoryStateRepository
    ) : ExecutionGateway {
        override suspend fun placeOrder(request: com.example.execution.domain.OrderRequest) =
            delegate.placeOrder(request)

        override suspend fun cancelOrder(request: com.example.execution.domain.OrderCancelRequest) =
            delegate.cancelOrder(request)

        override suspend fun replaceOrder(
            cancelRequest: com.example.execution.domain.OrderCancelRequest,
            newRequest: com.example.execution.domain.OrderRequest
        ) = delegate.replaceOrder(cancelRequest, newRequest)

        override suspend fun getOpenOrders(symbol: Symbol?) = delegate.getOpenOrders(symbol)

        override suspend fun getPositions(): List<com.example.account.domain.Position> {
            return inventory.getInventory().map { pos ->
                com.example.account.domain.Position(
                    symbol = pos.symbol,
                    quantity = pos.quantity,
                    averagePrice = pos.avgPrice
                )
            }
        }
    }
}
