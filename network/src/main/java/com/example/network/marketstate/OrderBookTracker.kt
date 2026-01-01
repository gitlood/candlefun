package com.example.network.marketstate

import com.example.network.dto.WsDepthUpdateData
import com.example.platform.model.BookLevel
import com.example.platform.model.OrderBook
import java.util.TreeMap

internal class OrderBookTracker {
    private val bids = TreeMap<Double, Double>(compareByDescending { it })
    private val asks = TreeMap<Double, Double>()

    var lastUpdateId: Long = 0L
        private set

    fun loadSnapshot(snapshot: OrderBook) {
        bids.clear()
        asks.clear()
        snapshot.bids.forEach { if (it.quantity > 0) bids[it.price] = it.quantity }
        snapshot.asks.forEach { if (it.quantity > 0) asks[it.price] = it.quantity }
        lastUpdateId = snapshot.lastUpdateId
    }

    fun applyUpdate(update: WsDepthUpdateData): OrderBookUpdateResult {
        if (lastUpdateId == 0L) return OrderBookUpdateResult(false, 0.0)

        val before = bestTop()
        val ok = applyNext(update)
        if (!ok) return OrderBookUpdateResult(false, 0.0)

        val after = bestTop()
        val ofi = computeOfi(before, after)
        return OrderBookUpdateResult(true, ofi)
    }

    fun bestBid(): BookLevel? = bids.firstKeyOrNull()?.let { BookLevel(it, bids[it] ?: 0.0) }

    fun bestAsk(): BookLevel? = asks.firstKeyOrNull()?.let { BookLevel(it, asks[it] ?: 0.0) }

    fun topBids(levels: Int): List<BookLevel> = bids.entries.take(levels).map { BookLevel(it.key, it.value) }

    fun topAsks(levels: Int): List<BookLevel> = asks.entries.take(levels).map { BookLevel(it.key, it.value) }

    fun depthImbalance(levels: Int): Double? {
        if (levels <= 0) return null
        val bidQty = bids.entries.take(levels).sumOf { it.value }
        val askQty = asks.entries.take(levels).sumOf { it.value }
        val total = bidQty + askQty
        if (total <= 0.0) return null
        return (bidQty - askQty) / total
    }

    private fun applyNext(update: WsDepthUpdateData): Boolean {
        val expected = lastUpdateId + 1

        if (update.finalUpdateId < expected) return true

        if (update.firstUpdateId <= expected) {
            applyDelta(update)
            lastUpdateId = update.finalUpdateId
            return true
        }

        return false
    }

    private fun applyDelta(update: WsDepthUpdateData) {
        for (lvl in update.bids) {
            if (lvl.size < 2) continue
            val p = lvl[0].toDoubleOrNull() ?: continue
            val q = lvl[1].toDoubleOrNull() ?: continue
            if (q <= 0.0) bids.remove(p) else bids[p] = q
        }
        for (lvl in update.asks) {
            if (lvl.size < 2) continue
            val p = lvl[0].toDoubleOrNull() ?: continue
            val q = lvl[1].toDoubleOrNull() ?: continue
            if (q <= 0.0) asks.remove(p) else asks[p] = q
        }
    }

    private fun bestTop(): BookTop {
        val bid = bestBid()
        val ask = bestAsk()
        return BookTop(bid?.price, bid?.quantity, ask?.price, ask?.quantity)
    }

    private fun computeOfi(before: BookTop, after: BookTop): Double {
        val beforeBidPrice = before.bidPrice
        val afterBidPrice = after.bidPrice
        val beforeAskPrice = before.askPrice
        val afterAskPrice = after.askPrice

        val beforeBidQty = before.bidQty ?: 0.0
        val afterBidQty = after.bidQty ?: 0.0
        val beforeAskQty = before.askQty ?: 0.0
        val afterAskQty = after.askQty ?: 0.0

        val bidDelta = when {
            beforeBidPrice == null || afterBidPrice == null -> 0.0
            afterBidPrice > beforeBidPrice -> afterBidQty
            afterBidPrice < beforeBidPrice -> -beforeBidQty
            else -> afterBidQty - beforeBidQty
        }

        val askDelta = when {
            beforeAskPrice == null || afterAskPrice == null -> 0.0
            afterAskPrice < beforeAskPrice -> afterAskQty
            afterAskPrice > beforeAskPrice -> -beforeAskQty
            else -> afterAskQty - beforeAskQty
        }

        return bidDelta - askDelta
    }

    private fun <K, V> TreeMap<K, V>.firstKeyOrNull(): K? = if (isEmpty()) null else firstKey()

    private data class BookTop(
        val bidPrice: Double?,
        val bidQty: Double?,
        val askPrice: Double?,
        val askQty: Double?
    )
}

internal data class OrderBookUpdateResult(
    val ok: Boolean,
    val ofiDelta: Double
)
