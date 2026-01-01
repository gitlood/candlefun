package com.example.avellaneda.quotes

import com.example.avellaneda.AvellanedaMmConfig
import com.example.avellaneda.QuoteStyle
import com.example.platform.model.MarketState

class QuoteCalculator(
    private val config: AvellanedaMmConfig
) {
    fun compute(state: MarketState, positionQty: Double, minSpreadPct: Double): QuoteResult? {
        val mid = state.midPrice ?: state.microPrice ?: return null
        val spread = state.spread ?: return null
        if (spread <= 0.0) return null

        val inventoryFraction = if (config.maxInventory > 0.0) {
            (positionQty / config.maxInventory).coerceIn(-1.0, 1.0)
        } else {
            0.0
        }
        val skew = inventoryFraction * config.inventorySkew * mid

        val minHalfSpread = (minSpreadPct * mid) / 2.0
        val baseHalfSpread = maxOf(spread / 2.0, minHalfSpread)
        val volAdj = (state.vol1s ?: 0.0) * config.volSpreadMultiplier * mid
        val halfSpread = baseHalfSpread + volAdj

        var bid = mid - halfSpread - skew
        var ask = mid + halfSpread - skew

        val bestBid = state.bestBidPrice
        val bestAsk = state.bestAskPrice
        when (config.quoteStyle) {
            QuoteStyle.JOIN -> {
                if (bestBid != null) bid = minOf(bid, bestBid)
                if (bestAsk != null) ask = maxOf(ask, bestAsk)
            }
            QuoteStyle.IMPROVE -> {
                if (bestBid != null) bid = maxOf(bid, bestBid + config.priceTick)
                if (bestAsk != null) ask = minOf(ask, bestAsk - config.priceTick)
            }
            QuoteStyle.WIDEN -> {
                if (bestBid != null) bid = minOf(bid, bestBid - config.priceTick)
                if (bestAsk != null) ask = maxOf(ask, bestAsk + config.priceTick)
            }
        }

        bid = roundDown(bid, config.priceTick)
        ask = roundUp(ask, config.priceTick)
        val minWidth = 2.0 * minHalfSpread
        if (ask - bid < minWidth) {
            val midAdj = (bid + ask) / 2.0
            bid = roundDown(midAdj - minHalfSpread, config.priceTick)
            ask = roundUp(midAdj + minHalfSpread, config.priceTick)
        }
        if (bid >= ask) return null

        return QuoteResult(bid = bid, ask = ask)
    }

    private fun roundDown(value: Double, step: Double): Double {
        if (step <= 0.0) return value
        return kotlin.math.floor(value / step) * step
    }

    private fun roundUp(value: Double, step: Double): Double {
        if (step <= 0.0) return value
        return kotlin.math.ceil(value / step) * step
    }
}
