package com.example.vacuum

import com.example.execution.impl.FillRecord
import com.example.platform.model.enums.OrderSide
import kotlin.math.abs

class VacuumKpiTracker(
    private val config: VacuumConfig
) {
    private val orderMeta = mutableMapOf<Long, VacuumOrderMeta>()
    private var slippageSumBps = 0.0
    private var slippageCount = 0
    private var tailLossCount = 0
    private var adverseSumBps = 0.0
    private var adverseCount = 0
    private var ordersPlaced = 0
    private var ordersCanceled = 0
    private var staleCancels = 0

    private val pendingAdverse = ArrayDeque<PendingAdverse>(128)
    private var lastSlippageBps: Double? = null

    fun onOrderPlaced(meta: VacuumOrderMeta) {
        orderMeta[meta.orderId] = meta
        ordersPlaced++
    }

    fun onOrderCanceled(orderId: Long, stale: Boolean) {
        ordersCanceled++
        if (stale) staleCancels++
        orderMeta.remove(orderId)
    }

    fun onFill(fill: FillRecord) {
        val meta = orderMeta[fill.orderId]
        if (meta != null) {
            val slip = computeSlippageBps(meta, fill)
            if (slip != null) {
                slippageSumBps += slip
                slippageCount++
                lastSlippageBps = slip
                if (slip > config.slippagePauseBps) {
                    tailLossCount++
                }
            }
            pendingAdverse.addLast(
                PendingAdverse(
                    symbol = fill.symbol.value,
                    side = fill.side,
                    fillPrice = fill.price.value.toDouble(),
                    fillTimeMs = fill.fillTimeMs
                )
            )
        }
    }

    fun onMarketState(symbol: String, midPrice: Double?, timestampMs: Long) {
        if (midPrice == null) return
        flushAdverse(symbol, midPrice, timestampMs)
    }

    fun summary(): VacuumKpiSummary {
        val avgSlip = if (slippageCount > 0) slippageSumBps / slippageCount else null
        val avgAdverse = if (adverseCount > 0) adverseSumBps / adverseCount else null
        val cancelRate = if (ordersPlaced > 0) ordersCanceled.toDouble() / ordersPlaced else null
        val staleRate = if (ordersPlaced > 0) staleCancels.toDouble() / ordersPlaced else null
        return VacuumKpiSummary(
            avgSlippageBps = avgSlip,
            avgAdverseMoveBps = avgAdverse,
            tailLossCount = tailLossCount,
            lastSlippageBps = lastSlippageBps,
            cancelRate = cancelRate,
            staleCancelRate = staleRate
        )
    }

    private fun computeSlippageBps(meta: VacuumOrderMeta, fill: FillRecord): Double? {
        val ref = if (fill.side == OrderSide.BUY) meta.bestAsk else meta.bestBid
        if (ref == null || ref <= 0.0) return null
        val fillPrice = fill.price.value.toDouble()
        val slip = if (fill.side == OrderSide.BUY) {
            (fillPrice - ref) / ref
        } else {
            (ref - fillPrice) / ref
        }
        return slip * 10_000.0
    }

    private fun flushAdverse(symbol: String, midPrice: Double, timestampMs: Long) {
        val horizonMs = 1_000L
        val toRemove = ArrayList<PendingAdverse>()
        for (pending in pendingAdverse) {
            if (pending.symbol != symbol) continue
            if (timestampMs - pending.fillTimeMs < horizonMs) continue
            val adverse = if (pending.side == OrderSide.BUY) {
                (pending.fillPrice - midPrice) / pending.fillPrice
            } else {
                (midPrice - pending.fillPrice) / pending.fillPrice
            }
            val adverseBps = adverse * 10_000.0
            adverseSumBps += adverseBps
            adverseCount++
            if (adverseBps > config.tailLossBps) tailLossCount++
            toRemove.add(pending)
        }
        if (toRemove.isNotEmpty()) {
            pendingAdverse.removeAll(toRemove)
        }
    }

    fun lastSlippageBps(): Double? = lastSlippageBps

    fun tailLossCount(): Int = tailLossCount

    private data class PendingAdverse(
        val symbol: String,
        val side: OrderSide,
        val fillPrice: Double,
        val fillTimeMs: Long
    )
}

data class VacuumKpiSummary(
    val avgSlippageBps: Double?,
    val avgAdverseMoveBps: Double?,
    val tailLossCount: Int,
    val lastSlippageBps: Double?,
    val cancelRate: Double?,
    val staleCancelRate: Double?
)
