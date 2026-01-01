package com.example.ofi.kukanov

import com.example.execution.impl.FillRecord
import com.example.platform.model.enums.OrderSide
import kotlin.math.abs

class OfiKpiTracker(
    private val makerFeePct: Double,
    private val takerFeePct: Double
) : OfiKpiSink {
    private val orderMeta = mutableMapOf<Long, OfiOrderMeta>()
    private val positions = mutableMapOf<String, PositionState>()
    private var totalFees = 0.0
    private var realizedPnl = 0.0
    private var slippageSumBps = 0.0
    private var slippageCount = 0
    private var joinEdgeSumBps = 0.0
    private var joinEdgeCount = 0
    private var latencySumMs = 0.0
    private var latencyCount = 0
    private var adverseSumBps = 0.0
    private var adverseCount = 0
    private var tradeCount = 0
    private var winCount = 0
    private var ordersPlaced = 0
    private var ordersCanceled = 0
    private var staleCancels = 0
    private var takeOrders = 0

    private val pendingAdverse = ArrayDeque<PendingAdverse>(128)

    override fun onOrderPlaced(meta: OfiOrderMeta) {
        orderMeta[meta.orderId] = meta
        ordersPlaced++
        if (meta.style == OfiOrderStyle.TAKE) takeOrders++
    }

    override fun onOrderCanceled(orderId: Long, stale: Boolean) {
        ordersCanceled++
        if (stale) staleCancels++
        orderMeta.remove(orderId)
    }

    fun onFill(fill: FillRecord) {
        val meta = orderMeta[fill.orderId]
        val feeRate = when (meta?.style) {
            OfiOrderStyle.TAKE -> takerFeePct
            else -> makerFeePct
        }
        val notional = fill.price.value.toDouble() * fill.quantity.value.toDouble()
        totalFees += notional * feeRate
        computeExecutionMetrics(meta, fill)
        if (meta != null) {
            val latency = fill.fillTimeMs - meta.timestampMs
            latencySumMs += latency.toDouble()
            latencyCount++
        }
        val realizedDelta = applyFillToPosition(fill)
        if (realizedDelta != 0.0 && positionQty(fill.symbol.value) == 0.0) {
            tradeCount++
            if (realizedDelta > 0.0) winCount++
        }
        if (meta != null) {
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
        positions.getOrPut(symbol) { PositionState() }.lastMid = midPrice
        flushAdverse(symbol, midPrice, timestampMs)
    }

    fun summary(): OfiKpiSummary {
        val unrealized = positions.values.sumOf { pos ->
            if (pos.qty == 0.0 || pos.lastMid == null) 0.0 else {
                val pnl = if (pos.qty > 0.0) {
                    (pos.lastMid!! - pos.avgPrice) * pos.qty
                } else {
                    (pos.avgPrice - pos.lastMid!!) * abs(pos.qty)
                }
                pnl
            }
        }
        val net = realizedPnl + unrealized - totalFees
        val avgSlip = if (slippageCount > 0) slippageSumBps / slippageCount else null
        val avgJoinEdge = if (joinEdgeCount > 0) joinEdgeSumBps / joinEdgeCount else null
        val avgLatency = if (latencyCount > 0) latencySumMs / latencyCount else null
        val avgAdverse = if (adverseCount > 0) adverseSumBps / adverseCount else null
        val winRate = if (tradeCount > 0) winCount.toDouble() / tradeCount else null
        val fillRate = if (ordersPlaced > 0) tradeCount.toDouble() / ordersPlaced else null
        val cancelRate = if (ordersPlaced > 0) ordersCanceled.toDouble() / ordersPlaced else null
        val staleCancelRate = if (ordersPlaced > 0) staleCancels.toDouble() / ordersPlaced else null
        val takeRate = if (ordersPlaced > 0) takeOrders.toDouble() / ordersPlaced else null
        return OfiKpiSummary(
            realizedPnl = realizedPnl,
            unrealizedPnl = unrealized,
            totalFees = totalFees,
            netPnl = net,
            avgSlippageBps = avgSlip,
            avgJoinEdgeBps = avgJoinEdge,
            avgAdverseMoveBps = avgAdverse,
            avgLatencyMs = avgLatency,
            tradeCount = tradeCount,
            winRate = winRate,
            fillRate = fillRate,
            cancelRate = cancelRate,
            staleCancelRate = staleCancelRate,
            takeRate = takeRate
        )
    }

    private fun computeExecutionMetrics(meta: OfiOrderMeta?, fill: FillRecord) {
        if (meta == null) return
        val fillPrice = fill.price.value.toDouble()
        if (meta.style == OfiOrderStyle.JOIN) {
            val mid = meta.expectedMid
            if (mid != null && mid > 0.0) {
                val edge = if (fill.side == OrderSide.BUY) {
                    (mid - fillPrice) / mid
                } else {
                    (fillPrice - mid) / mid
                }
                joinEdgeSumBps += edge * 10_000.0
                joinEdgeCount++
            }
            return
        }
        val ref = if (fill.side == OrderSide.BUY) meta.bestAsk else meta.bestBid
        if (ref != null && ref > 0.0) {
            val slip = if (fill.side == OrderSide.BUY) {
                (fillPrice - ref) / ref
            } else {
                (ref - fillPrice) / ref
            }
            slippageSumBps += slip * 10_000.0
            slippageCount++
        }
    }

    private fun applyFillToPosition(fill: FillRecord): Double {
        val symbol = fill.symbol.value
        val pos = positions.getOrPut(symbol) { PositionState() }
        val fillQty = fill.quantity.value.toDouble()
        val fillPrice = fill.price.value.toDouble()
        val signedQty = if (fill.side == OrderSide.BUY) fillQty else -fillQty

        if (pos.qty == 0.0 || sameDirection(pos.qty, signedQty)) {
            val newQty = pos.qty + signedQty
            pos.avgPrice = if (newQty == 0.0) 0.0 else {
                val cost = pos.avgPrice * abs(pos.qty) + fillPrice * abs(signedQty)
                cost / abs(newQty)
            }
            pos.qty = newQty
            return 0.0
        }

        val closingQty = minOf(abs(pos.qty), abs(signedQty))
        val realizedDelta = if (pos.qty > 0.0) {
            (fillPrice - pos.avgPrice) * closingQty
        } else {
            (pos.avgPrice - fillPrice) * closingQty
        }
        realizedPnl += realizedDelta

        val remainingQty = pos.qty + signedQty
        if (remainingQty == 0.0) {
            pos.qty = 0.0
            pos.avgPrice = 0.0
        } else if (sameDirection(remainingQty, signedQty)) {
            pos.qty = remainingQty
            pos.avgPrice = fillPrice
        } else {
            pos.qty = remainingQty
        }
        return realizedDelta
    }

    private fun positionQty(symbol: String): Double {
        return positions[symbol]?.qty ?: 0.0
    }

    private fun sameDirection(a: Double, b: Double): Boolean {
        return (a > 0.0 && b > 0.0) || (a < 0.0 && b < 0.0)
    }

    private fun flushAdverse(symbol: String, midPrice: Double, timestampMs: Long) {
        val horizonMs = 1_000L
        val iter = pendingAdverse.iterator()
        val toRemove = ArrayList<PendingAdverse>()
        while (iter.hasNext()) {
            val pending = iter.next()
            if (pending.symbol != symbol) continue
            if (timestampMs - pending.fillTimeMs < horizonMs) continue
            val adverse = if (pending.side == OrderSide.BUY) {
                (pending.fillPrice - midPrice) / pending.fillPrice
            } else {
                (midPrice - pending.fillPrice) / pending.fillPrice
            }
            adverseSumBps += adverse * 10_000.0
            adverseCount++
            toRemove.add(pending)
        }
        if (toRemove.isNotEmpty()) {
            pendingAdverse.removeAll(toRemove)
        }
    }

    private data class PositionState(
        var qty: Double = 0.0,
        var avgPrice: Double = 0.0,
        var lastMid: Double? = null
    )

    private data class PendingAdverse(
        val symbol: String,
        val side: OrderSide,
        val fillPrice: Double,
        val fillTimeMs: Long
    )
}

data class OfiKpiSummary(
    val realizedPnl: Double,
    val unrealizedPnl: Double,
    val totalFees: Double,
    val netPnl: Double,
    val avgSlippageBps: Double?,
    val avgJoinEdgeBps: Double?,
    val avgAdverseMoveBps: Double?,
    val avgLatencyMs: Double?,
    val tradeCount: Int,
    val winRate: Double?,
    val fillRate: Double?,
    val cancelRate: Double?,
    val staleCancelRate: Double?,
    val takeRate: Double?
)
