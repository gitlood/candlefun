package com.example.marketdata.impl.recording

import com.example.marketdata.model.MarketStateConfig
import com.example.marketdata.model.Symbol
import com.example.marketdata.repository.FuturesMarketStateRepository
import com.example.marketdata.repository.MarketStateRepository
import com.example.platform.model.MarketState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

class MarketStateRecorder(
    private val outputFile: File,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private var job: Job? = null

    fun startSpot(
        repo: MarketStateRepository,
        symbols: List<Symbol>,
        config: MarketStateConfig = MarketStateConfig()
    ) {
        start(repo.streamMarketState(symbols, config))
    }

    fun startFutures(
        repo: FuturesMarketStateRepository,
        symbols: List<Symbol>,
        config: MarketStateConfig = MarketStateConfig()
    ) {
        start(repo.streamMarketState(symbols, config))
    }

    private fun start(flow: kotlinx.coroutines.flow.Flow<MarketState>) {
        if (job != null) return
        outputFile.parentFile?.mkdirs()
        if (!outputFile.exists()) outputFile.writeText(header())

        job = scope.launch {
            flow.collect { state ->
                outputFile.appendText(serialize(state))
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun header(): String {
        return "symbol,timestampMs,eventTimeMs,bestBidPrice,bestBidQty,bestAskPrice,bestAskQty,midPrice,spread,microPrice,depthImbalance,ofi1s,tradeCount1s,tradeVolume1s,tradeImbalance1s,lastTradePrice,lastTradeQty,lastTradeIsBuyerMaker,vol1s,vol5s,vol10s,vol1m,vol5m,bookUpdateId,bidLevels,askLevels\n"
    }

    private fun serialize(s: MarketState): String {
        return buildString {
            append(s.symbol).append(',')
            append(s.timestampMs).append(',')
            append(s.eventTimeMs ?: 0L).append(',')
            append(s.bestBidPrice ?: 0.0).append(',')
            append(s.bestBidQty ?: 0.0).append(',')
            append(s.bestAskPrice ?: 0.0).append(',')
            append(s.bestAskQty ?: 0.0).append(',')
            append(s.midPrice ?: 0.0).append(',')
            append(s.spread ?: 0.0).append(',')
            append(s.microPrice ?: 0.0).append(',')
            append(s.depthImbalance ?: 0.0).append(',')
            append(s.ofi1s).append(',')
            append(s.tradeCount1s).append(',')
            append(s.tradeVolume1s).append(',')
            append(s.tradeImbalance1s).append(',')
            append(s.lastTradePrice ?: 0.0).append(',')
            append(s.lastTradeQty ?: 0.0).append(',')
            append(s.lastTradeIsBuyerMaker ?: false).append(',')
            append(s.vol1s ?: 0.0).append(',')
            append(s.vol5s ?: 0.0).append(',')
            append(s.vol10s ?: 0.0).append(',')
            append(s.vol1m ?: 0.0).append(',')
            append(s.vol5m ?: 0.0).append(',')
            append(s.bookUpdateId).append(',')
            append(encodeLevels(s.bidLevels)).append(',')
            append(encodeLevels(s.askLevels)).append('\n')
        }
    }

    private fun encodeLevels(levels: List<com.example.platform.model.BookLevel>): String {
        if (levels.isEmpty()) return ""
        return levels.joinToString(";") { "${it.price}:${it.quantity}" }
    }
}
