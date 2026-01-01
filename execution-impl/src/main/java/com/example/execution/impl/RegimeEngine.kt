package com.example.execution.impl

import com.example.execution.domain.StrategyRegimeState
import com.example.platform.model.MarketState
import kotlin.math.abs

class RegimeEngine(
    private val minSpreadBps: Double,
    private val maxSpreadBps: Double,
    private val maxVol1s: Double,
    private val maxTradeImbalance1s: Double,
    private val minTradeCount1s: Int
) {
    fun evaluate(state: MarketState): Map<String, StrategyRegimeState> {
        val mid = state.midPrice ?: state.microPrice ?: return emptyMap()
        val spread = state.spread ?: return emptyMap()
        if (mid <= 0.0 || spread <= 0.0) return emptyMap()
        val spreadBps = (spread / mid) * 10_000.0
        val vol = state.vol1s ?: 0.0
        val toxic =
            state.tradeCount1s >= minTradeCount1s && abs(state.tradeImbalance1s) >= maxTradeImbalance1s
        val volatile = spreadBps >= maxSpreadBps || vol >= maxVol1s
        val calm = spreadBps <= minSpreadBps && vol > 0.0 && vol < (maxVol1s * 0.5)

        return when {
            toxic -> mapOf(
                "avellaneda_mm" to StrategyRegimeState("avellaneda_mm", enabled = false, scale = 0.0, reason = "toxic"),
                "ofi_kukanov" to StrategyRegimeState("ofi_kukanov", enabled = true, scale = 0.5, reason = "toxic"),
                "vacuum" to StrategyRegimeState("vacuum", enabled = true, scale = 1.0, reason = "toxic"),
                "survivor" to StrategyRegimeState("survivor", enabled = true, scale = 0.7, reason = "toxic"),
                "pairs" to StrategyRegimeState("pairs", enabled = false, scale = 0.0, reason = "toxic")
            )
            volatile -> mapOf(
                "avellaneda_mm" to StrategyRegimeState("avellaneda_mm", enabled = false, scale = 0.0, reason = "volatile"),
                "ofi_kukanov" to StrategyRegimeState("ofi_kukanov", enabled = true, scale = 1.0, reason = "volatile"),
                "vacuum" to StrategyRegimeState("vacuum", enabled = true, scale = 1.0, reason = "volatile"),
                "survivor" to StrategyRegimeState("survivor", enabled = true, scale = 1.0, reason = "volatile"),
                "pairs" to StrategyRegimeState("pairs", enabled = false, scale = 0.0, reason = "volatile")
            )
            calm -> mapOf(
                "avellaneda_mm" to StrategyRegimeState("avellaneda_mm", enabled = true, scale = 1.0, reason = "calm"),
                "ofi_kukanov" to StrategyRegimeState("ofi_kukanov", enabled = true, scale = 0.5, reason = "calm"),
                "vacuum" to StrategyRegimeState("vacuum", enabled = false, scale = 0.0, reason = "calm"),
                "survivor" to StrategyRegimeState("survivor", enabled = true, scale = 1.0, reason = "calm"),
                "pairs" to StrategyRegimeState("pairs", enabled = true, scale = 1.0, reason = "calm")
            )
            else -> mapOf(
                "avellaneda_mm" to StrategyRegimeState("avellaneda_mm", enabled = true, scale = 1.0, reason = "default"),
                "ofi_kukanov" to StrategyRegimeState("ofi_kukanov", enabled = true, scale = 1.0, reason = "default"),
                "vacuum" to StrategyRegimeState("vacuum", enabled = false, scale = 0.0, reason = "default"),
                "survivor" to StrategyRegimeState("survivor", enabled = true, scale = 1.0, reason = "default"),
                "pairs" to StrategyRegimeState("pairs", enabled = true, scale = 1.0, reason = "default")
            )
        }
    }

    companion object {
        fun fromEnv(): RegimeEngine {
            val minSpread = System.getenv("REGIME_MIN_SPREAD_BPS")?.toDoubleOrNull() ?: 1.5
            val maxSpread = System.getenv("REGIME_MAX_SPREAD_BPS")?.toDoubleOrNull() ?: 30.0
            val maxVol = System.getenv("REGIME_MAX_VOL_1S")?.toDoubleOrNull() ?: 0.01
            val maxTradeImb = System.getenv("REGIME_MAX_TRADE_IMB_1S")?.toDoubleOrNull() ?: 1.0
            val minTradeCount = System.getenv("REGIME_MIN_TRADE_COUNT_1S")?.toIntOrNull() ?: 10
            return RegimeEngine(
                minSpreadBps = minSpread,
                maxSpreadBps = maxSpread,
                maxVol1s = maxVol,
                maxTradeImbalance1s = maxTradeImb,
                minTradeCount1s = minTradeCount
            )
        }
    }
}
