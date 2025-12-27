package com.example.survivor

import com.example.platform.model.enums.OrderSide
import kotlin.math.abs

class SurvivorKpiTracker(
    private val config: SurvivorConfig
) {
    private var realizedFunding = 0.0
    private var realizedFees = 0.0
    private var borrowCosts = 0.0
    private var expectedCarry = 0.0
    private var worstBasisAbs = 0.0
    private var lastBorrowTs: Long? = null
    private var positionQty = 0.0
    private var positionPrice = 0.0
    private var lastMark: Double? = null
    private var lastFundingTs: Long? = null
    private var ordersPlaced = 0
    private var ordersCanceled = 0
    private var staleCancels = 0

    fun onOrderPlaced(orderId: Long, snapshot: SurvivorSnapshot, targetSide: SurvivorSide) {
        ordersPlaced++
        if (targetSide != SurvivorSide.FLAT) {
            worstBasisAbs = abs(snapshot.basisPct).coerceAtLeast(worstBasisAbs)
        }
    }

    fun onOrderCanceled(orderId: Long, stale: Boolean) {
        ordersCanceled++
        if (stale) staleCancels++
    }

    fun onFill(fill: SurvivorFill) {
        val notional = fill.price.value.toDouble() * fill.quantity.value.toDouble()
        realizedFees += notional * config.takerFeePct
        val signedQty = if (fill.side == OrderSide.BUY) fill.quantity.toDouble() else -fill.quantity.toDouble()
        positionQty += signedQty
        positionPrice = fill.price.value.toDouble()
    }

    fun onMark(snapshot: SurvivorSnapshot, side: SurvivorSide) {
        lastMark = snapshot.markPrice
        worstBasisAbs = abs(snapshot.basisPct).coerceAtLeast(worstBasisAbs)
        updateBorrowCosts(snapshot.timestampMs)
        maybeApplyFunding(snapshot)
    }

    fun onPositionUpdate(symbol: String, qty: Double, avgPrice: Double?) {
        positionQty = qty
        if (avgPrice != null && avgPrice > 0.0) {
            positionPrice = avgPrice
        }
    }

    private fun maybeApplyFunding(snapshot: SurvivorSnapshot) {
        val nextTs = snapshot.nextFundingTimeMs
        val lastTs = lastFundingTs
        if (lastTs == null || nextTs > lastTs) {
            if (snapshot.timestampMs >= nextTs) {
                val qty = positionQty
                if (qty != 0.0) {
                    val payment = -qty * snapshot.markPrice * snapshot.fundingRate
                    realizedFunding += payment
                    expectedCarry += payment
                }
                lastFundingTs = nextTs
            }
        }
    }

    private fun updateBorrowCosts(nowMs: Long) {
        val last = lastBorrowTs
        lastBorrowTs = nowMs
        if (last == null) return
        val qty = positionQty
        if (qty == 0.0) return
        val price = lastMark ?: return
        val notional = abs(qty) * price
        val dtDays = (nowMs - last).coerceAtLeast(0L) / 86_400_000.0
        borrowCosts += notional * config.borrowFeePctPerDay * dtDays
    }

    fun summary(): SurvivorKpiSummary {
        val net = realizedFunding - realizedFees - borrowCosts
        val cancelRate = if (ordersPlaced > 0) ordersCanceled.toDouble() / ordersPlaced else null
        val staleRate = if (ordersPlaced > 0) staleCancels.toDouble() / ordersPlaced else null
        return SurvivorKpiSummary(
            realizedFunding = realizedFunding,
            realizedFees = realizedFees,
            borrowCosts = borrowCosts,
            netCarry = net,
            expectedCarry = expectedCarry,
            worstBasisAbsPct = worstBasisAbs,
            cancelRate = cancelRate,
            staleCancelRate = staleRate
        )
    }
}

data class SurvivorKpiSummary(
    val realizedFunding: Double,
    val realizedFees: Double,
    val borrowCosts: Double,
    val netCarry: Double,
    val expectedCarry: Double,
    val worstBasisAbsPct: Double,
    val cancelRate: Double?,
    val staleCancelRate: Double?
)
