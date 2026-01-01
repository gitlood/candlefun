package com.example.survivor

import com.example.account.domain.Qty
import com.example.account.domain.Symbol
import com.example.execution.domain.IntentUrgency
import com.example.execution.domain.StrategyContext
import com.example.execution.domain.StrategyIntent
import com.example.platform.report.Telemetry
import com.example.survivor.util.RollingOiWindow
import kotlin.math.abs

class SurvivorIntentStrategy(
    private val config: SurvivorConfig
) {
    private val oiWindow = RollingOiWindow(config.oiWindowMs)
    private var side: SurvivorSide = SurvivorSide.FLAT
    private var entryTimeMs: Long? = null

    fun onSnapshot(snapshot: SurvivorSnapshot, context: StrategyContext): List<StrategyIntent> {
        if (snapshot.symbol != config.symbol) return emptyList()
        val now = snapshot.timestampMs
        oiWindow.add(now, snapshot.openInterest)
        syncPosition(context)

        val regimeOk = regimeOk(snapshot)
        val desired = desiredSide(snapshot)
        Telemetry.emit(
            type = "strategy_signal",
            tsMs = now,
            data = mapOf(
                "strategy_id" to "survivor",
                "symbol" to config.symbol,
                "funding_rate" to snapshot.fundingRate,
                "basis_pct" to snapshot.basisPct,
                "volatility" to snapshot.volatility,
                "spread_pct" to snapshot.spreadPct,
                "open_interest" to snapshot.openInterest,
                "regime_ok" to regimeOk,
                "desired_side" to desired.name
            )
        )
        if (!regimeOk) return emptyList()

        if (side == SurvivorSide.FLAT && desired == SurvivorSide.FLAT) {
            return emptyList()
        }
        if (side == SurvivorSide.FLAT && desired != SurvivorSide.FLAT) {
            if (abs(snapshot.basisPct) > config.entryBasisAbsPctMax) return emptyList()
            val confidence = entryConfidence(snapshot)
            return listOf(enterIntent(desired, snapshot, confidence))
        } else if (side != SurvivorSide.FLAT) {
            if (shouldExit(snapshot)) {
                return listOf(exitIntent(snapshot))
            }
        }
        return emptyList()
    }

    private fun regimeOk(snapshot: SurvivorSnapshot): Boolean {
        if (snapshot.volatility > config.maxVolatility) return false
        if (snapshot.spreadPct > config.maxSpreadPct) return false
        if (oiWindow.isJumping(snapshot.openInterest, config.maxOiJumpPct)) return false
        return true
    }

    private fun desiredSide(snapshot: SurvivorSnapshot): SurvivorSide {
        return when {
            snapshot.fundingRate >= config.entryFundingThreshold -> SurvivorSide.SHORT_PERP
            snapshot.fundingRate <= -config.entryFundingThreshold -> SurvivorSide.LONG_PERP
            else -> SurvivorSide.FLAT
        }
    }

    private fun shouldExit(snapshot: SurvivorSnapshot): Boolean {
        val entryMs = entryTimeMs ?: return false
        if (abs(snapshot.fundingRate) <= config.exitFundingThreshold) return true
        if (abs(snapshot.basisPct) >= config.basisStopAbsPct) return true
        if (snapshot.volatility > config.maxVolatility) return true
        if (snapshot.timestampMs - entryMs >= config.maxHoldMs) return true
        return false
    }

    private fun enterIntent(
        desired: SurvivorSide,
        snapshot: SurvivorSnapshot,
        confidence: Double
    ): StrategyIntent {
        val orderSide = if (desired == SurvivorSide.LONG_PERP) 1.0 else -1.0
        val signedQty = orderSide * config.orderQty
        val timeToFundingMs = snapshot.nextFundingTimeMs - snapshot.timestampMs
        val preferMaker = timeToFundingMs > config.maxTimeToFundingForTakerMs
        val urgency = if (preferMaker) IntentUrgency.LOW else IntentUrgency.MEDIUM
        side = desired
        entryTimeMs = snapshot.timestampMs
        Telemetry.emit(
            type = "strategy_intent",
            tsMs = snapshot.timestampMs,
            data = mapOf(
                "strategy_id" to "survivor",
                "symbol" to config.symbol,
                "desired_delta" to signedQty,
                "urgency" to urgency.name,
                "prefer_maker" to preferMaker,
                "ttl_ms" to config.orderTtlMs,
                "reason" to "funding_entry",
                "confidence" to confidence
            )
        )
        return StrategyIntent(
            strategyId = "survivor",
            symbol = Symbol.of(config.symbol),
            desiredDelta = Qty.fromDouble(signedQty),
            urgency = urgency,
            preferMaker = preferMaker,
            ttlMs = config.orderTtlMs,
            confidence = confidence,
            riskBudgetRequest = abs(signedQty),
            reason = "funding_entry"
        )
    }

    private fun exitIntent(snapshot: SurvivorSnapshot): StrategyIntent {
        val orderSide = if (side == SurvivorSide.LONG_PERP) -1.0 else 1.0
        val signedQty = orderSide * config.orderQty
        side = SurvivorSide.FLAT
        entryTimeMs = null
        Telemetry.emit(
            type = "strategy_intent",
            tsMs = snapshot.timestampMs,
            data = mapOf(
                "strategy_id" to "survivor",
                "symbol" to config.symbol,
                "desired_delta" to signedQty,
                "urgency" to "HIGH",
                "prefer_maker" to true,
                "ttl_ms" to config.orderTtlMs,
                "reason" to "funding_exit"
            )
        )
        return StrategyIntent(
            strategyId = "survivor",
            symbol = Symbol.of(config.symbol),
            desiredDelta = Qty.fromDouble(signedQty),
            urgency = IntentUrgency.HIGH,
            preferMaker = true,
            ttlMs = config.orderTtlMs,
            confidence = 1.0,
            riskBudgetRequest = abs(signedQty),
            reason = "funding_exit"
        )
    }

    private fun syncPosition(context: StrategyContext) {
        val qty = context.positionQty(Symbol.of(config.symbol))
        val nextSide = when {
            qty > 0.0 -> SurvivorSide.LONG_PERP
            qty < 0.0 -> SurvivorSide.SHORT_PERP
            else -> SurvivorSide.FLAT
        }
        if (nextSide != side) {
            side = nextSide
            entryTimeMs = if (side == SurvivorSide.FLAT) null else context.nowMs
        }
    }

    private fun entryConfidence(snapshot: SurvivorSnapshot): Double {
        val threshold = config.entryFundingThreshold
        if (threshold <= 0.0) return 0.5
        val strength = abs(snapshot.fundingRate) / threshold
        return strength.coerceIn(0.0, 1.0)
    }
}
