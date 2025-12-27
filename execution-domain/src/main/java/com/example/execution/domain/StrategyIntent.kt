package com.example.execution.domain

import com.example.account.domain.Price
import com.example.account.domain.Qty
import com.example.account.domain.Symbol
import com.example.platform.model.enums.OrderSide
import com.example.platform.model.enums.OrderType
import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.pow

/**
 * Declarative desired exposure change from a strategy. Intents are netted and risk-filtered before
 * any orders are routed.
 */
data class StrategyIntent(
    val strategyId: String,
    val symbol: Symbol,
    val desiredDelta: Qty,
    val urgency: IntentUrgency = IntentUrgency.MEDIUM,
    val maxSlippageBps: Double? = null,
    val preferMaker: Boolean = true,
    val ttlMs: Long? = null,
    /** 0.0–1.0 confidence score of the edge quality. */
    val confidence: Double = 1.0,
    /** Optional risk units requested; if null, abs(desiredDelta) is used. */
    val riskBudgetRequest: Double? = null,
    val reason: String? = null
) {
    init {
        require(strategyId.isNotBlank()) { "strategyId cannot be blank" }
        require(symbol.value.isNotBlank()) { "symbol cannot be blank" }
        require(confidence >= 0.0) { "confidence cannot be negative" }
        require(maxSlippageBps?.let { it >= 0.0 } ?: true) { "maxSlippageBps must be positive when set" }
    }
}

enum class IntentUrgency {
    LOW,
    MEDIUM,
    HIGH
}

/** Regime or gate decision for a strategy. */
data class StrategyRegimeState(
    val strategyId: String,
    val enabled: Boolean = true,
    /** Optional scaling factor applied to the requested delta (0 = fully gated). */
    val scale: Double = 1.0,
    val reason: String? = null
)

/** Portfolio-level risk budget configuration. */
data class RiskBudget(
    val total: Double,
    val perStrategyCap: Map<String, Double> = emptyMap(),
    /**
     * Exponent applied to confidence when computing weights. p=1 keeps weights linear, p>1 makes
     * the allocator pickier (higher confidence intents get more size).
     */
    val confidenceExponent: Double = 1.0
)

/** Accepted intent allocation after applying regime gates and risk budgets. */
data class IntentAllocation(
    val intent: StrategyIntent,
    val acceptedDelta: Qty,
    val appliedScale: Double,
    val rejectionReason: String? = null
)

/** Per-symbol netting summary. */
data class NettingSummary(
    val symbol: Symbol,
    val totalBuy: Qty,
    val totalSell: Qty,
    val crossQty: Qty,
    val netDelta: Qty,
    val allocations: List<IntentAllocation>
) {
    val netSide: OrderSide?
        get() = when {
            netDelta.value > BigDecimal.ZERO -> OrderSide.BUY
            netDelta.value < BigDecimal.ZERO -> OrderSide.SELL
            else -> null
        }
}

/** Result of routing a netted decision. */
data class RoutingDecision(
    val symbol: Symbol,
    val netDelta: Qty,
    val price: Price?,
    val orderType: OrderType,
    val preferMaker: Boolean,
    val maxSlippageBps: Double?,
    val clientOrderId: String? = null
)

internal fun confidenceWeight(confidence: Double, exponent: Double): Double {
    if (confidence <= 0.0) return 0.0
    if (confidence >= 1.0) return 1.0
    return confidence.pow(exponent)
}

internal fun riskUnits(intent: StrategyIntent, scaledDelta: Qty): Double {
    return intent.riskBudgetRequest ?: abs(scaledDelta.value.toDouble())
}
