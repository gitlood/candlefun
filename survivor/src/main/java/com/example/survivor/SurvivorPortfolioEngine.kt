package com.example.survivor

import com.example.account.domain.Qty
import com.example.account.domain.Symbol
import com.example.execution.domain.ExecutionGateway
import com.example.execution.domain.StrategyContext
import com.example.execution.domain.StrategyRegimeState
import com.example.execution.domain.StrategyIntent
import com.example.execution.impl.ExecutionPolicy
import com.example.execution.impl.IntentAllocator

class SurvivorPortfolioEngine(
    private val gateway: ExecutionGateway,
    private val allocator: IntentAllocator,
    private val policy: ExecutionPolicy,
    private val strategy: SurvivorIntentStrategy,
    private val positionRefreshMs: Long = 5_000L
) {
    private var lastPositionRefreshMs: Long = 0L
    private var positions = emptyMap<Symbol, Qty>()

    suspend fun onSnapshot(snapshot: SurvivorSnapshot) {
        val now = snapshot.timestampMs
        refreshPositions(now)
        val context = StrategyContext(positions = positions, nowMs = now)
        val intents = strategy.onSnapshot(snapshot, context)
        route(intents, now, snapshot)
    }

    private suspend fun route(intents: List<StrategyIntent>, nowMs: Long, snapshot: SurvivorSnapshot) {
        if (intents.isEmpty()) return
        val (_, decisions) = allocator.allocate(intents, emptyMap<String, StrategyRegimeState>())
        for (decision in decisions) {
            if (decision.symbol.value != snapshot.symbol) continue
            policy.route(
                decision,
                snapshot.asMarketState(),
                nowMs
            )
        }
    }

    private suspend fun refreshPositions(nowMs: Long) {
        if (nowMs - lastPositionRefreshMs < positionRefreshMs) return
        val next = gateway.getPositions().associate { it.symbol to it.quantity }
        positions = next
        lastPositionRefreshMs = nowMs
    }
}

private fun SurvivorSnapshot.asMarketState(): com.example.platform.model.MarketState {
    return com.example.platform.model.MarketState(
        symbol = symbol,
        timestampMs = timestampMs,
        eventTimeMs = timestampMs,
        bestBidPrice = null,
        bestBidQty = null,
        bestAskPrice = null,
        bestAskQty = null,
        midPrice = markPrice,
        spread = null,
        microPrice = null,
        depthImbalance = null,
        ofi1s = 0.0,
        tradeCount1s = 0,
        tradeVolume1s = 0.0,
        tradeImbalance1s = 0.0,
        lastTradePrice = null,
        lastTradeQty = null,
        lastTradeIsBuyerMaker = null,
        vol1s = null,
        vol5s = null,
        vol10s = null,
        vol1m = null,
        vol5m = null,
        bookUpdateId = 0L,
        bidLevels = emptyList(),
        askLevels = emptyList()
    )
}
