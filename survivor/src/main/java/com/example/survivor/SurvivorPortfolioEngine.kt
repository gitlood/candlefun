package com.example.survivor

import com.example.account.domain.Qty
import com.example.account.domain.Symbol
import com.example.execution.domain.ExecutionGateway
import com.example.execution.domain.StrategyContext
import com.example.execution.impl.ExecutionPolicy
import com.example.execution.impl.IntentAllocator
import com.example.execution.impl.PortfolioEngine
import com.example.platform.report.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SurvivorPortfolioEngine(
    private val gateway: ExecutionGateway,
    private val allocator: IntentAllocator,
    private val policy: ExecutionPolicy,
    private val strategy: SurvivorIntentStrategy,
    private val positionRefreshMs: Long = 5_000L,
    private val heartbeatMs: Long = 2_000L,
    private val staleFeedMs: Long = 10_000L
) {
    private val portfolioEngine = PortfolioEngine(
        gateway = gateway,
        allocator = allocator,
        policy = policy,
        strategies = emptyList()
    )
    private var lastPositionRefreshMs: Long = 0L
    private var positions = emptyMap<Symbol, Qty>()
    private val mutex = Mutex()
    private var lastSnapshot: SurvivorSnapshot? = null
    private var lastSnapshotMs: Long = 0L
    private var lastHeartbeatMs: Long = 0L
    private var lastFlattenMs: Long = 0L

    suspend fun onSnapshot(snapshot: SurvivorSnapshot) {
        mutex.withLock {
            lastSnapshot = snapshot
            lastSnapshotMs = snapshot.timestampMs
            val now = snapshot.timestampMs
            refreshPositions(now)
            val context = StrategyContext(positions = positions, nowMs = now)
            val intents = strategy.onSnapshot(snapshot, context)
            if (intents.isEmpty()) return
            portfolioEngine.onExternalIntents(snapshot.asMarketState(), intents)
        }
    }

    fun startHeartbeat(scope: CoroutineScope): Job {
        return scope.launch {
            while (isActive) {
                delay(heartbeatMs)
                val nowMs = System.currentTimeMillis()
                onHeartbeat(nowMs)
            }
        }
    }

    private suspend fun refreshPositions(nowMs: Long) {
        if (nowMs - lastPositionRefreshMs < positionRefreshMs) return
        val next = gateway.getPositions().associate { it.symbol to it.quantity }
        positions = next
        lastPositionRefreshMs = nowMs
    }

    private suspend fun onHeartbeat(nowMs: Long) {
        mutex.withLock {
            if (nowMs - lastHeartbeatMs < heartbeatMs) return
            lastHeartbeatMs = nowMs
            refreshPositions(nowMs)
            val snapshot = lastSnapshot ?: return
            val ageMs = nowMs - lastSnapshotMs
            if (ageMs > staleFeedMs) {
                if (nowMs - lastFlattenMs >= staleFeedMs) {
                    lastFlattenMs = nowMs
                    Telemetry.emit(
                        type = "survivor_stale_feed",
                        tsMs = nowMs,
                        data = mapOf(
                            "symbol" to snapshot.symbol,
                            "age_ms" to ageMs,
                            "stale_feed_ms" to staleFeedMs
                        )
                    )
                    emergencyFlatten(snapshot)
                }
                return
            }
            val context = StrategyContext(positions = positions, nowMs = nowMs)
            val intents = strategy.onSnapshot(snapshot, context)
            if (intents.isEmpty()) return
            portfolioEngine.onExternalIntents(snapshot.asMarketState(), intents)
        }
    }

    private suspend fun emergencyFlatten(snapshot: SurvivorSnapshot) {
        val symbol = Symbol.of(snapshot.symbol)
        val open = gateway.getOpenOrders(symbol)
        for (order in open) {
            runCatching {
                gateway.cancelOrder(
                    com.example.execution.domain.OrderCancelRequest(symbol = symbol, orderId = order.orderId)
                )
            }
        }
        val qty = positions[symbol]?.toDouble() ?: 0.0
        if (qty == 0.0) return
        val intent = com.example.execution.domain.StrategyIntent(
            strategyId = "survivor",
            symbol = symbol,
            desiredDelta = Qty.fromDouble(-qty),
            urgency = com.example.execution.domain.IntentUrgency.HIGH,
            preferMaker = false,
            ttlMs = 1_000L,
            confidence = 1.0,
            riskBudgetRequest = kotlin.math.abs(qty),
            reason = "stale_feed_flatten"
        )
        portfolioEngine.onExternalIntents(snapshot.asMarketState(), listOf(intent))
    }
}
