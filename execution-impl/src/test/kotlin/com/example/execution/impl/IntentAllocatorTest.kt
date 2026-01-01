package com.example.execution.impl

import com.example.account.domain.Qty
import com.example.account.domain.Symbol
import com.example.execution.domain.RiskBudget
import com.example.execution.domain.StrategyIntent
import com.example.execution.domain.StrategyRegimeState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IntentAllocatorTest {

    private val symbol = Symbol.of("BTCUSDT")

    @Test
    fun `nets opposing intents and routes single delta`() {
        val intents = listOf(
            StrategyIntent(strategyId = "mm", symbol = symbol, desiredDelta = Qty.fromDouble(1.0)),
            StrategyIntent(strategyId = "scalper", symbol = symbol, desiredDelta = Qty.fromDouble(-0.4))
        )

        val allocator = IntentAllocator(riskBudget = RiskBudget(total = 100.0))
        val (net, routed) = allocator.allocate(intents)

        assertEquals(1, net.size)
        assertEquals(0.6, net.first().netDelta.value.toDouble(), 1e-9)
        assertEquals(1, routed.size)
        assertEquals(0.6, routed.first().netDelta.value.toDouble(), 1e-9)
    }

    @Test
    fun `applies regime gate to disable strategy`() {
        val intents = listOf(
            StrategyIntent(strategyId = "mm", symbol = symbol, desiredDelta = Qty.fromDouble(1.0))
        )
        val regimes = mapOf("mm" to StrategyRegimeState(strategyId = "mm", enabled = false, reason = "toxic"))

        val allocator = IntentAllocator(riskBudget = RiskBudget(total = 100.0))
        val (net, routed) = allocator.allocate(intents, regimes)

        assertEquals(Qty.ZERO, net.first().netDelta)
        assertTrue(routed.isEmpty())
        assertEquals("toxic", net.first().allocations.first().rejectionReason)
    }

    @Test
    fun `respects per-strategy cap`() {
        val intents = listOf(
            StrategyIntent(strategyId = "a", symbol = symbol, desiredDelta = Qty.fromDouble(5.0), confidence = 1.0),
            StrategyIntent(strategyId = "b", symbol = symbol, desiredDelta = Qty.fromDouble(5.0), confidence = 1.0)
        )
        val budget = RiskBudget(total = 10.0, perStrategyCap = mapOf("a" to 2.0, "b" to 8.0))

        val allocator = IntentAllocator(riskBudget = budget)
        val riskAllocator = RiskAllocator(budget)
        val budgets = riskAllocator.allocateBudgets(setOf("a", "b"), mapOf("a" to 1.0, "b" to 1.0))
        val (_, routed) = allocator.allocate(intents, emptyMap(), budgets)

        assertEquals(1, routed.size)
        // Risk cap throttles strategy a to 2.0 and leaves strategy b at 5.0 (requested < cap).
        assertEquals(7.0, routed.first().netDelta.value.toDouble(), 1e-9)
    }

    @Test
    fun `uses confidence to throttle`() {
        val intents = listOf(
            StrategyIntent(strategyId = "hi", symbol = symbol, desiredDelta = Qty.fromDouble(4.0), confidence = 1.0),
            StrategyIntent(strategyId = "lo", symbol = symbol, desiredDelta = Qty.fromDouble(4.0), confidence = 0.25)
        )
        val budget = RiskBudget(total = 4.0, confidenceExponent = 2.0)

        val allocator = IntentAllocator(riskBudget = budget)
        val (_, routed) = allocator.allocate(intents)

        assertEquals(1, routed.size)
        // Confidence scaling reduces the low-quality intent; total budget caps to 4.0.
        assertEquals(4.0, routed.first().netDelta.value.toDouble(), 1e-9)
    }

    @Test
    fun `returns null routing when net flat`() {
        val intents = listOf(
            StrategyIntent(strategyId = "a", symbol = symbol, desiredDelta = Qty.fromDouble(1.0)),
            StrategyIntent(strategyId = "b", symbol = symbol, desiredDelta = Qty.fromDouble(-1.0))
        )

        val allocator = IntentAllocator(riskBudget = RiskBudget(total = 100.0))
        val (net, routed) = allocator.allocate(intents)

        assertEquals(0.0, net.first().netDelta.value.toDouble(), 1e-9)
        assertTrue(routed.isEmpty())
    }
}
