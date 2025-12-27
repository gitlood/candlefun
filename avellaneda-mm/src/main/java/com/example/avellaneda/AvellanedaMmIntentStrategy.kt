package com.example.avellaneda

import com.example.account.domain.Qty
import com.example.account.domain.Symbol
import com.example.avellaneda.gates.RegimeGate
import com.example.avellaneda.quotes.QuoteCalculator
import com.example.execution.domain.IntentStrategy
import com.example.execution.domain.StrategyContext
import com.example.execution.domain.StrategyIntent
import com.example.execution.domain.IntentUrgency
import com.example.platform.model.MarketState
import com.example.platform.report.Telemetry
import kotlin.math.max

class AvellanedaMmIntentStrategy(
    override val id: String = "avellaneda_mm",
    private val config: AvellanedaMmConfig,
    private val adverseBpsProvider: ((String) -> Double?)? = null
) : IntentStrategy {
    private var lastActionMs: Long = 0L
    private var lastGateReason: String? = null
    private var lastGateMs: Long = 0L
    private var adaptiveMinSpreadPct: Double = config.minSpreadPct
    private var lastAdaptiveUpdateMs: Long = 0L
    private val gate = RegimeGate(config)
    private val quoter = QuoteCalculator(config)

    override fun onMarketState(state: MarketState, context: StrategyContext): List<StrategyIntent> {
        if (state.symbol != config.symbol) return emptyList()
        val now = state.eventTimeMs ?: state.timestampMs
        if (now - lastActionMs < config.quoteRefreshMs) return emptyList()

        val mid = state.midPrice ?: state.microPrice ?: return emptyList()
        val spread = state.spread ?: return emptyList()
        if (spread <= 0.0) return emptyList()
        val spreadPct = spread / mid
        gate.addSpreadSample(now, spreadPct)
        val gateReason = gate.check(state, spreadPct, now)
        Telemetry.emit(
            type = "strategy_signal",
            tsMs = now,
            data = mapOf(
                "strategy_id" to id,
                "symbol" to config.symbol,
                "spread_pct" to spreadPct,
                "mid" to mid,
                "vol_1s" to state.vol1s,
                "depth_imbalance" to state.depthImbalance,
                "gate_reason" to gateReason
            )
        )
        if (gateReason != null) {
            if (config.logGateDecisions && gateReason != lastGateReason) {
                println("gate=${config.symbol} reason=$gateReason")
                lastGateReason = gateReason
            }
            lastGateMs = now
            lastActionMs = now
            return emptyList()
        }
        lastGateReason = null
        if (now - lastGateMs < config.gateCooldownMs) {
            if (config.logGateDecisions && lastGateReason != "cooldown") {
                println("gate=${config.symbol} reason=cooldown")
                lastGateReason = "cooldown"
            }
            lastActionMs = now
            return emptyList()
        }

        val positionQty = context.positionQty(Symbol.of(config.symbol))
        updateAdaptiveSpread(now)
        val quote = quoter.compute(state, positionQty, adaptiveMinSpreadPct) ?: return emptyList()
        val bid = quote.bid
        val ask = quote.ask
        val bestBid = state.bestBidPrice
        val bestAsk = state.bestAskPrice

        val allowBid = positionQty < config.maxInventory && (bestAsk == null || bid < bestAsk)
        val allowAsk = positionQty > -config.maxInventory && (bestBid == null || ask > bestBid)

        val intents = ArrayList<StrategyIntent>(2)
        if (allowBid) {
            intents.add(
                StrategyIntent(
                    strategyId = id,
                    symbol = Symbol.of(config.symbol),
                    desiredDelta = Qty.fromDouble(config.orderQty),
                    urgency = IntentUrgency.LOW,
                    preferMaker = true,
                    ttlMs = config.maxQuoteAgeMs,
                    confidence = 1.0,
                    reason = "quote_bid"
                )
            )
            Telemetry.emit(
                type = "strategy_intent",
                tsMs = now,
                data = mapOf(
                    "strategy_id" to id,
                    "symbol" to config.symbol,
                    "desired_delta" to config.orderQty,
                    "urgency" to "LOW",
                    "prefer_maker" to true,
                    "ttl_ms" to config.maxQuoteAgeMs,
                    "limit_price" to bid,
                    "reason" to "quote_bid"
                )
            )
        }
        if (allowAsk) {
            intents.add(
                StrategyIntent(
                    strategyId = id,
                    symbol = Symbol.of(config.symbol),
                    desiredDelta = Qty.fromDouble(-config.orderQty),
                    urgency = IntentUrgency.LOW,
                    preferMaker = true,
                    ttlMs = config.maxQuoteAgeMs,
                    confidence = 1.0,
                    reason = "quote_ask"
                )
            )
            Telemetry.emit(
                type = "strategy_intent",
                tsMs = now,
                data = mapOf(
                    "strategy_id" to id,
                    "symbol" to config.symbol,
                    "desired_delta" to -config.orderQty,
                    "urgency" to "LOW",
                    "prefer_maker" to true,
                    "ttl_ms" to config.maxQuoteAgeMs,
                    "limit_price" to ask,
                    "reason" to "quote_ask"
                )
            )
        }

        lastActionMs = now
        return intents
    }

    private fun updateAdaptiveSpread(nowMs: Long) {
        val targetBps = config.adaptiveSpreadTargetBps ?: return
        if (nowMs - lastAdaptiveUpdateMs < config.adaptiveSpreadUpdateMs) return
        lastAdaptiveUpdateMs = nowMs

        val advBpsRaw = adverseBpsProvider?.invoke(config.symbol) ?: 0.0
        val toxicityBps = max(0.0, advBpsRaw)
        val feeBps = config.makerFeePct * 10_000.0
        val requiredBps = (2.0 * feeBps) + targetBps + (2.0 * toxicityBps)
        adaptiveMinSpreadPct = (requiredBps / 10_000.0).coerceAtLeast(config.minSpreadPct)
        if (config.logGateDecisions) {
            println(
                "adaptiveSpread=${config.symbol} minSpreadPct=${"%.6f".format(adaptiveMinSpreadPct)} " +
                    "feeBps=${"%.2f".format(feeBps)} advBps=${"%.2f".format(advBpsRaw)}"
            )
        }
    }
}
