package com.example.avellaneda.metrics

import com.example.platform.model.MarketState
import com.example.platform.model.enums.OrderSide

class AdverseSelectionTracker(
    private val horizonsMs: LongArray = longArrayOf(1_000L, 5_000L)
) {
    private val pending = ArrayDeque<PendingFill>()
    private val sumsBySymbol = mutableMapOf<String, DoubleArray>()
    private val countsBySymbol = mutableMapOf<String, IntArray>()

    fun recordFill(symbol: String, side: OrderSide, price: Double, timestampMs: Long) {
        pending.addLast(PendingFill(symbol, side, price, timestampMs, BooleanArray(horizonsMs.size)))
    }

    fun onMarketState(state: MarketState) {
        val mid = state.midPrice ?: state.lastTradePrice ?: return
        val now = state.eventTimeMs ?: state.timestampMs
        if (pending.isEmpty()) return

        val iter = pending.iterator()
        while (iter.hasNext()) {
            val fill = iter.next()
            if (fill.symbol != state.symbol) continue
            val sums = sumsFor(fill.symbol)
            val counts = countsFor(fill.symbol)
            var doneCount = 0
            for (i in horizonsMs.indices) {
                if (fill.done[i]) {
                    doneCount++
                    continue
                }
                if (now - fill.timestampMs >= horizonsMs[i]) {
                    val adverse = adverseMove(fill.side, fill.price, mid)
                    sums[i] += adverse
                    counts[i] += 1
                    fill.done[i] = true
                    doneCount++
                }
            }
            if (doneCount == horizonsMs.size) {
                iter.remove()
            }
        }
    }

    fun snapshotBps(symbol: String): List<Double?> {
        val sums = sumsBySymbol[symbol] ?: return horizonsMs.map { null }
        val counts = countsBySymbol[symbol] ?: return horizonsMs.map { null }
        return horizonsMs.indices.map { i ->
            if (counts[i] == 0) null else (sums[i] / counts[i]) * 10_000.0
        }
    }

    fun snapshotBpsMap(): Map<String, List<Double?>> {
        return sumsBySymbol.keys.associateWith { snapshotBps(it) }
    }

    fun horizonsLabel(): List<String> = horizonsMs.map { "${it / 1000}s" }

    private fun adverseMove(side: OrderSide, fillPrice: Double, midAfter: Double): Double {
        return when (side) {
            OrderSide.BUY -> (fillPrice - midAfter) / fillPrice
            OrderSide.SELL -> (midAfter - fillPrice) / fillPrice
        }
    }

    private data class PendingFill(
        val symbol: String,
        val side: OrderSide,
        val price: Double,
        val timestampMs: Long,
        val done: BooleanArray
    )

    private fun sumsFor(symbol: String): DoubleArray {
        return sumsBySymbol.getOrPut(symbol) { DoubleArray(horizonsMs.size) }
    }

    private fun countsFor(symbol: String): IntArray {
        return countsBySymbol.getOrPut(symbol) { IntArray(horizonsMs.size) }
    }
}
