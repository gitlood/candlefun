package com.example.network.marketstate

import com.example.marketdata.model.MarketStateConfig
import com.example.platform.model.MarketState
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal class MarketStateBuilder(
    private val config: MarketStateConfig
) {
    fun build(symbol: String, state: SymbolState, nowMs: Long): MarketState {
        val bookTicker = state.lastBookTicker
        val bestBid = bookTicker?.bestBidPrice?.toDoubleOrNull() ?: state.orderBook.bestBid()?.price
        val bestBidQty = bookTicker?.bestBidQty?.toDoubleOrNull() ?: state.orderBook.bestBid()?.quantity
        val bestAsk = bookTicker?.bestAskPrice?.toDoubleOrNull() ?: state.orderBook.bestAsk()?.price
        val bestAskQty = bookTicker?.bestAskQty?.toDoubleOrNull() ?: state.orderBook.bestAsk()?.quantity

        val spread = if (bestBid != null && bestAsk != null) bestAsk - bestBid else null
        val mid = if (bestBid != null && bestAsk != null) (bestBid + bestAsk) / 2.0 else null
        val microPrice = if (bestBid != null && bestAsk != null && bestBidQty != null && bestAskQty != null) {
            val denom = bestBidQty + bestAskQty
            if (denom > 0.0) (bestBid * bestAskQty + bestAsk * bestBidQty) / denom else null
        } else {
            null
        }

        val tradeStats = state.tradeWindow.snapshot(nowMs)
        val tradeImbalance = tradeStats.buyVolume - tradeStats.sellVolume

        val vol1s = state.volatility.sigma(1.seconds, nowMs)
        val vol5s = state.volatility.sigma(5.seconds, nowMs)
        val vol10s = state.volatility.sigma(10.seconds, nowMs)
        val vol1m = state.volatility.sigma(1.minutes, nowMs)
        val vol5m = state.volatility.sigma(5.minutes, nowMs)

        val eventTime = maxOf(
            state.lastBookEventTime ?: 0L,
            state.lastDepthEventTime ?: 0L,
            state.lastTradeEventTime ?: 0L
        ).takeIf { it > 0L }

        return MarketState(
            symbol = symbol,
            timestampMs = nowMs,
            eventTimeMs = eventTime,
            bestBidPrice = bestBid,
            bestBidQty = bestBidQty,
            bestAskPrice = bestAsk,
            bestAskQty = bestAskQty,
            midPrice = mid,
            spread = spread,
            microPrice = microPrice,
            depthImbalance = state.orderBook.depthImbalance(config.depthLevels),
            ofi1s = state.ofiWindow.current(nowMs),
            tradeCount1s = tradeStats.count,
            tradeVolume1s = tradeStats.volume,
            tradeImbalance1s = tradeImbalance,
            lastTradePrice = state.lastTrade?.price?.toDoubleOrNull(),
            lastTradeQty = state.lastTrade?.quantity?.toDoubleOrNull(),
            lastTradeIsBuyerMaker = state.lastTrade?.isBuyerMaker,
            vol1s = vol1s,
            vol5s = vol5s,
            vol10s = vol10s,
            vol1m = vol1m,
            vol5m = vol5m,
            bookUpdateId = state.orderBook.lastUpdateId,
            bidLevels = state.orderBook.topBids(config.depthLevels),
            askLevels = state.orderBook.topAsks(config.depthLevels)
        )
    }
}
