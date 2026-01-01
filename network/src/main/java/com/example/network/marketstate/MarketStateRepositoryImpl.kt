package com.example.network.marketstate

import com.example.marketdata.model.MarketStateConfig
import com.example.marketdata.model.Symbol
import com.example.marketdata.repository.MarketStateRepository
import com.example.network.interfaces.BinanceOrderBookService
import com.example.network.interfaces.LiveAggTradeRepo
import com.example.network.interfaces.LiveBookTickerRepo
import com.example.network.interfaces.LiveDepthRepo
import com.example.platform.model.MarketState
import com.example.platform.report.Telemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.random.Random

internal class MarketStateRepositoryImpl(
    private val bookTickerRepo: LiveBookTickerRepo,
    private val depthRepo: LiveDepthRepo,
    private val tradeRepo: LiveAggTradeRepo,
    private val orderBookService: BinanceOrderBookService,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) : MarketStateRepository {
    private val snapshotSemaphore = Semaphore(1)
    private val lastTelemetryMs = mutableMapOf<String, Long>()
    private val telemetryIntervalMs = System.getenv("TELEMETRY_MARKET_SNAPSHOT_MS")?.toLongOrNull() ?: 0L
    private val telemetrySamplePct =
        System.getenv("TELEMETRY_MARKET_SNAPSHOT_SAMPLE_PCT")?.toDoubleOrNull()?.coerceIn(0.0, 100.0) ?: 100.0

    override fun streamMarketState(
        symbols: List<Symbol>,
        config: MarketStateConfig
    ): Flow<MarketState> = channelFlow {
        val normalized = symbols.map { it.value }.distinct()
        val builder = MarketStateBuilder(config)
        val assembler = MarketStateAssembler(config, builder)
        val resyncer = SnapshotResyncer(config.snapshotThrottle)

        assembler.ensureSymbols(normalized)

        normalized.forEach { symbol ->
            val now = clockMs()
            if (resyncer.tryStart(symbol, now)) {
                assembler.startDepthResync(symbol)
                launch { resyncSnapshot(symbol, assembler, resyncer, config) }
            }
        }

        launch {
            bookTickerRepo.streamBookTickers(normalized).collect { data ->
                val now = data.eventTime ?: clockMs()
                assembler.onBookTicker(data, now)
            }
        }

        launch {
            depthRepo.streamDepthUpdates(normalized, config.depthSpeed).collect { data ->
                val now = data.eventTime ?: clockMs()
                val needsResync = assembler.onDepthUpdate(data, now)
                if (needsResync && resyncer.tryStart(data.symbol.uppercase(), now)) {
                    assembler.startDepthResync(data.symbol.uppercase())
                    launch { resyncSnapshot(data.symbol.uppercase(), assembler, resyncer, config) }
                }
            }
        }

        launch {
            tradeRepo.streamAggTrades(normalized).collect { data ->
                val now = if (data.tradeTime > 0) data.tradeTime else data.eventTime ?: clockMs()
                assembler.onAggTrade(data, now)
            }
        }

        launch(Dispatchers.Default) {
            while (isActive) {
                delay(config.tick.inWholeMilliseconds)
                val now = clockMs()
                for (symbol in normalized) {
                    val snapshot = assembler.build(symbol, now) ?: continue
                    maybeEmitTelemetry(snapshot)
                    send(snapshot)
                }
            }
        }
    }

    private suspend fun resyncSnapshot(
        symbol: String,
        assembler: MarketStateAssembler,
        resyncer: SnapshotResyncer,
        config: MarketStateConfig
    ) {
        try {
            snapshotSemaphore.withPermit {
                val snapshot = orderBookService.getDepth(symbol, config.snapshotDepthLimit)
                assembler.onSnapshot(symbol, snapshot)
            }
            resyncer.markSuccess(symbol, clockMs())
        } catch (_: Exception) {
            resyncer.markFailure(symbol)
        }
    }

    private fun depthTopNotional(snapshot: MarketState): Double {
        var sum = 0.0
        for (level in snapshot.bidLevels) sum += level.price * level.quantity
        for (level in snapshot.askLevels) sum += level.price * level.quantity
        return sum
    }

    private fun maybeEmitTelemetry(snapshot: MarketState) {
        if (telemetrySamplePct <= 0.0) return
        if (telemetrySamplePct < 100.0 && Random.nextDouble(0.0, 100.0) > telemetrySamplePct) return
        val now = snapshot.eventTimeMs ?: snapshot.timestampMs
        val last = lastTelemetryMs[snapshot.symbol] ?: 0L
        if (telemetryIntervalMs > 0L && now - last < telemetryIntervalMs) return
        lastTelemetryMs[snapshot.symbol] = now
        Telemetry.emit(
            type = "market_snapshot",
            tsMs = now,
            data = mapOf(
                "symbol" to snapshot.symbol,
                "mid" to snapshot.midPrice,
                "spread" to snapshot.spread,
                "microprice" to snapshot.microPrice,
                "best_bid" to snapshot.bestBidPrice,
                "best_ask" to snapshot.bestAskPrice,
                "depth_top_notional" to depthTopNotional(snapshot),
                "depth_imbalance" to snapshot.depthImbalance,
                "ofi_1s" to snapshot.ofi1s,
                "trade_count_1s" to snapshot.tradeCount1s,
                "trade_imbalance_1s" to snapshot.tradeImbalance1s,
                "vol_1s" to snapshot.vol1s,
                "vol_5s" to snapshot.vol5s,
                "vol_10s" to snapshot.vol10s
            )
        )
    }
}
