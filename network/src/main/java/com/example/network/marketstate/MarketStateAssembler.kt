package com.example.network.marketstate

import com.example.marketdata.model.MarketStateConfig
import com.example.network.dto.WsAggTradeData
import com.example.network.dto.WsBookTickerData
import com.example.network.dto.WsDepthUpdateData
import com.example.platform.model.MarketState
import com.example.platform.model.OrderBook
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

internal class MarketStateAssembler(
    private val config: MarketStateConfig,
    private val builder: MarketStateBuilder
) {
    private val states = ConcurrentHashMap<String, SymbolState>()

    fun ensureSymbols(symbols: List<String>) {
        symbols.forEach { symbol ->
            states.putIfAbsent(symbol, SymbolState(config))
        }
    }

    suspend fun startDepthResync(symbol: String) {
        val state = states[symbol] ?: return
        state.lock.withLock {
            state.depthSync.start()
        }
    }

    suspend fun onBookTicker(data: WsBookTickerData, nowMs: Long) {
        val symbol = data.symbol.uppercase()
        val state = states[symbol] ?: return
        val bid = data.bestBidPrice.toDoubleOrNull()
        val ask = data.bestAskPrice.toDoubleOrNull()
        val mid = if (bid != null && ask != null) (bid + ask) / 2.0 else null

        state.lock.withLock {
            state.lastBookTicker = data
            state.lastBookEventTime = data.eventTime
            if (mid != null) state.volatility.addPrice(nowMs, mid)
        }
    }

    suspend fun onDepthUpdate(data: WsDepthUpdateData, nowMs: Long): Boolean {
        val symbol = data.symbol.uppercase()
        val state = states[symbol] ?: return false
        val res = state.lock.withLock {
            if (state.depthSync.isSyncing) {
                state.depthSync.buffer(data)
                val result = state.depthSync.tryApply(state.orderBook) { u, applyResult ->
                    if (applyResult.ok) {
                        val ts = u.eventTime ?: nowMs
                        state.ofiWindow.add(ts, applyResult.ofiDelta)
                        state.lastDepthEventTime = u.eventTime
                    }
                }
                return@withLock when (result) {
                    DepthSyncResult.MISSED_BRIDGE, DepthSyncResult.FAILED -> OrderBookUpdateResult(false, 0.0)
                    else -> OrderBookUpdateResult(true, 0.0)
                }
            }
            val result = state.orderBook.applyUpdate(data)
            if (result.ok) {
                state.ofiWindow.add(nowMs, result.ofiDelta)
                state.lastDepthEventTime = data.eventTime
            }
            result
        }
        return !res.ok
    }

    suspend fun onAggTrade(data: WsAggTradeData, nowMs: Long) {
        val symbol = data.symbol.uppercase()
        val state = states[symbol] ?: return
        val qty = data.quantity.toDoubleOrNull() ?: return

        state.lock.withLock {
            state.lastTrade = data
            state.lastTradeEventTime = if (data.tradeTime > 0) data.tradeTime else data.eventTime
            state.tradeWindow.add(nowMs, qty, data.isBuyerMaker)
        }
    }

    suspend fun onSnapshot(symbol: String, snapshot: OrderBook) {
        val state = states[symbol] ?: return
        state.lock.withLock {
            if (!state.depthSync.isSyncing) {
                state.orderBook.loadSnapshot(snapshot)
                return@withLock
            }

            state.depthSync.setSnapshot(snapshot)
            state.depthSync.tryApply(state.orderBook) { u, applyResult ->
                if (applyResult.ok) {
                    val ts = u.eventTime ?: System.currentTimeMillis()
                    state.ofiWindow.add(ts, applyResult.ofiDelta)
                    state.lastDepthEventTime = u.eventTime
                }
            }
        }
    }

    suspend fun build(symbol: String, nowMs: Long): MarketState? {
        val state = states[symbol] ?: return null
        return state.lock.withLock { builder.build(symbol, state, nowMs) }
    }
}
