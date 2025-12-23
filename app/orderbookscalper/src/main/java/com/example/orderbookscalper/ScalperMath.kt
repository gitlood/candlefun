package com.example.orderbookscalper

import com.example.network.model.OrderBookLevel
import kotlin.math.pow
import kotlin.math.sqrt

fun computeMid(bestBid: Double, bestAsk: Double): Double = (bestBid + bestAsk) / 2.0

fun computeSpread(bestBid: Double, bestAsk: Double): Double = bestAsk - bestBid

fun computeSpreadPct(mid: Double, spread: Double): Double {
    if (mid <= 0.0) return 0.0
    return spread / mid
}

fun computeImbalance(bids: List<OrderBookLevel>, asks: List<OrderBookLevel>, depth: Int): Double {
    val bidDepth = bids.take(depth).sumOf { it.quantity }
    val askDepth = asks.take(depth).sumOf { it.quantity }
    val denom = bidDepth + askDepth
    if (denom <= 0.0) return 0.0
    return (bidDepth - askDepth) / denom
}

fun computeNotionalDepth(levels: List<OrderBookLevel>, depth: Int): Double {
    return levels.take(depth).sumOf { it.price * it.quantity }
}

fun computeMicroPrice(bestBid: Double, bestAsk: Double, bidQty1: Double, askQty1: Double): Double? {
    val denom = bidQty1 + askQty1
    if (denom <= 0.0) return null
    return (bestBid * askQty1 + bestAsk * bidQty1) / denom
}

fun computeMicroEdge(microPrice: Double?, mid: Double, spread: Double): Double? {
    if (microPrice == null || spread <= 0.0) return null
    return (microPrice - mid) / spread
}

fun computeStdDev(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    val mean = values.sum() / values.size
    val variance = values.sumOf { (it - mean).pow(2) } / values.size
    return sqrt(variance)
}

fun computeVolProxyPct(samples: Collection<PriceSample>): Double {
    if (samples.size < 2) return 0.0
    val ordered = samples.sortedBy { it.timestamp }
    val returns = ArrayList<Double>(ordered.size - 1)
    for (i in 1 until ordered.size) {
        val prev = ordered[i - 1].mid
        val curr = ordered[i].mid
        if (prev > 0.0) returns.add((curr - prev) / prev)
    }
    return computeStdDev(returns)
}
