package com.example.pairs

import kotlin.math.abs

class PairsKpiTracker(private val config: PairsConfig) {
    private var entryTimeMs: Long? = null
    private var halfLifeSumMs = 0L
    private var halfLifeCount = 0
    private var tailEvents = 0
    private var realizedPnL = 0.0
    private var totalFees = 0.0
    private var openSide: PairSide = PairSide.FLAT
    private var entrySpread: Double? = null
    private var entryZ: Double? = null

    fun onEntry(timestampMs: Long, spread: Double, z: Double) {
        entryTimeMs = timestampMs
        entrySpread = spread
        entryZ = z
    }

    fun onExit(timestampMs: Long, spread: Double, notional: Double) {
        val entry = entryTimeMs
        if (entry != null) {
            halfLifeSumMs += (timestampMs - entry)
            halfLifeCount++
        }
        val delta = when (openSide) {
            PairSide.LONG_A_SHORT_B -> spread - (entrySpread ?: spread)
            PairSide.SHORT_A_LONG_B -> (entrySpread ?: spread) - spread
            PairSide.FLAT -> 0.0
        }
        realizedPnL += delta * notional
        entryTimeMs = null
        entrySpread = null
        entryZ = null
    }

    fun onTailEvent(z: Double) {
        if (abs(z) >= config.tailZ) tailEvents++
    }

    fun onFees(notional: Double, taker: Boolean = true) {
        val feeRate = if (taker) config.takerFeePct else config.makerFeePct
        totalFees += notional * feeRate
    }

    fun onOpenSide(side: PairSide) {
        openSide = side
    }

    fun summary(): PairsKpiSummary {
        val halfLifeMs = if (halfLifeCount > 0) halfLifeSumMs / halfLifeCount else null
        val net = realizedPnL - totalFees
        return PairsKpiSummary(
            avgHalfLifeMs = halfLifeMs,
            tailEvents = tailEvents,
            realizedPnL = realizedPnL,
            totalFees = totalFees,
            netPnL = net
        )
    }
}

data class PairsKpiSummary(
    val avgHalfLifeMs: Long?,
    val tailEvents: Int,
    val realizedPnL: Double,
    val totalFees: Double,
    val netPnL: Double
)
