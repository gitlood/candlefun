package com.example.execution.domain

import com.example.account.domain.Qty
import com.example.account.domain.Symbol
import com.example.platform.model.MarketState

data class StrategyContext(
    val positions: Map<Symbol, Qty>,
    val nowMs: Long
) {
    fun positionQty(symbol: Symbol): Double {
        return positions[symbol]?.toDouble() ?: 0.0
    }
}

interface IntentStrategy {
    val id: String
    fun onMarketState(state: MarketState, context: StrategyContext): List<StrategyIntent>
}
