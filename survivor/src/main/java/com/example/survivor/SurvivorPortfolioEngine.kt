package com.example.survivor

import com.example.account.domain.Qty
import com.example.account.domain.Symbol
import com.example.execution.domain.ExecutionGateway
import com.example.execution.domain.StrategyContext
import com.example.execution.impl.ExecutionPolicy
import com.example.execution.impl.IntentAllocator
import com.example.execution.impl.PortfolioEngine

class SurvivorPortfolioEngine(
    private val gateway: ExecutionGateway,
    private val allocator: IntentAllocator,
    private val policy: ExecutionPolicy,
    private val strategy: SurvivorIntentStrategy,
    private val positionRefreshMs: Long = 5_000L
) {
    private val portfolioEngine = PortfolioEngine(
        gateway = gateway,
        allocator = allocator,
        policy = policy,
        strategies = emptyList()
    )
    private var lastPositionRefreshMs: Long = 0L
    private var positions = emptyMap<Symbol, Qty>()

    suspend fun onSnapshot(snapshot: SurvivorSnapshot) {
        val now = snapshot.timestampMs
        refreshPositions(now)
        val context = StrategyContext(positions = positions, nowMs = now)
        val intents = strategy.onSnapshot(snapshot, context)
        if (intents.isEmpty()) return
        portfolioEngine.onExternalIntents(snapshot.asMarketState(), intents)
    }

    private suspend fun refreshPositions(nowMs: Long) {
        if (nowMs - lastPositionRefreshMs < positionRefreshMs) return
        val next = gateway.getPositions().associate { it.symbol to it.quantity }
        positions = next
        lastPositionRefreshMs = nowMs
    }
}
