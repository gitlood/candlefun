package com.example.historicaldata.util

import com.example.network.model.OrderBookDepth
import com.example.network.model.OrderBookLevel
import com.example.platformutil.model.OrderBookSnapshot
import kotlin.math.max

object OrderBookFeatureCalculator {
    fun buildSnapshot(symbol: String, depth: OrderBookDepth, timestamp: Long): OrderBookSnapshot {
        val bestBid = depth.bids.firstOrNull()?.price ?: 0.0
        val bestAsk = depth.asks.firstOrNull()?.price ?: 0.0
        val mid = if (bestBid > 0.0 && bestAsk > 0.0) (bestBid + bestAsk) / 2.0 else 0.0
        val spread = if (bestBid > 0.0 && bestAsk > 0.0) max(0.0, bestAsk - bestBid) else 0.0

        val bidDepth10 = sumDepth(depth.bids, 10)
        val askDepth10 = sumDepth(depth.asks, 10)
        val imbalance10 = imbalance(bidDepth10, askDepth10)

        val bidDepth20 = sumDepth(depth.bids, 20)
        val askDepth20 = sumDepth(depth.asks, 20)
        val imbalance20 = imbalance(bidDepth20, askDepth20)

        return OrderBookSnapshot(
            timestamp = timestamp,
            symbol = symbol,
            bestBid = bestBid,
            bestAsk = bestAsk,
            midPrice = mid,
            spread = spread,
            bidDepth10 = bidDepth10,
            askDepth10 = askDepth10,
            imbalance10 = imbalance10,
            bidDepth20 = bidDepth20,
            askDepth20 = askDepth20,
            imbalance20 = imbalance20,
            updateId = depth.lastUpdateId
        )
    }

    private fun sumDepth(levels: List<OrderBookLevel>, n: Int): Double {
        if (levels.isEmpty()) return 0.0
        val limit = n.coerceAtMost(levels.size)
        var total = 0.0
        for (i in 0 until limit) {
            total += levels[i].quantity
        }
        return total
    }

    private fun imbalance(bidDepth: Double, askDepth: Double): Double {
        val denom = bidDepth + askDepth
        return if (denom <= 0.0) 0.0 else (bidDepth - askDepth) / denom
    }
}
