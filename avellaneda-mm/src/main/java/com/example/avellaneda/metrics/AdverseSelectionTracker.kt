package com.example.avellaneda.metrics

import com.example.platform.model.MarketState
import com.example.platform.model.enums.OrderSide

class AdverseSelectionTracker(
    private val horizonsMs: LongArray = longArrayOf(1_000L, 5_000L)
) {
    private val pending = ArrayDeque<PendingFill>()
    private val sums = DoubleArray(horizonsMs.size)
    private val counts = IntArray(horizonsMs.size)

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

    fun snapshotBps(): List<Double?> {
        return horizonsMs.indices.map { i ->
            if (counts[i] == 0) null else (sums[i] / counts[i]) * 10_000.0
        }
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
}
