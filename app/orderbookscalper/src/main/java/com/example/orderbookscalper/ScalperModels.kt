package com.example.orderbookscalper

import com.example.network.AggTrade

enum class Side {
    LONG,
    SHORT
}

enum class ExitPriceBasis {
    BEST_BID_ASK,
    MID,
    LAST_TRADE
}

data class PriceSample(
    val timestamp: Long,
    val mid: Double
)

data class BookSnapshot(
    val timestamp: Long,
    val bestBid: Double,
    val bestAsk: Double,
    val bidQty1: Double,
    val askQty1: Double,
    val mid: Double,
    val spread: Double,
    val spreadPct: Double,
    val imbalance: Double,
    val microPrice: Double?,
    val microEdge: Double?,
    val bidDepthNotional: Double,
    val askDepthNotional: Double
)

// Models.kt (or wherever your data classes live)

data class FlowSnapshot(
    val buyQty: Double,
    val sellQty: Double,
    val totalQty: Double,
    val totalNotional: Double,
    val imbalance: Double,
    val tradeCount: Int
)

data class SymbolState(
    val symbol: String,
    val midSamples: ArrayDeque<PriceSample> = ArrayDeque(),
    val tradeWindow: ArrayDeque<AggTrade> = ArrayDeque(),
    var pendingOrder: PendingOrder? = null,
    var position: Position? = null,
    var cooldownUntil: Long = 0L,
    var lastUpdateMs: Long = 0L,
    var lastAggTradeId: Long? = null,
    var lastTradePrice: Double? = null,
    var lastTradeTimestamp: Long? = null,

    // NEW: signal persistence (reduces flicker place/cancel spam)
    var longSignalStreak: Int = 0,
    var shortSignalStreak: Int = 0
)


data class PendingOrder(
    val side: Side,
    val price: Double,
    val quantity: Double,
    val placedAt: Long,
    val ttlMs: Long,
    val entrySpread: Double,
    val entryOffset: Double,
    var filledQuantity: Double = 0.0
)

data class Position(
    val side: Side,
    val entryPrice: Double,
    val entryTime: Long,
    val quantity: Double,
    val entrySpread: Double,
    val tp: Double,
    val sl: Double,
    val trailArm: Double,
    val trailMult: Double,
    val entryMid: Double,
    val maxHoldMs: Long,
    val entryImbalance: Double,
    val entryMicroPrice: Double?,
    val entryMicroEdge: Double?,
    val entryFlowImbalance: Double,
    val entryVolProxyPct: Double,
    val entrySpreadPct: Double,
    val entryBidDepthNotional: Double,
    val entryAskDepthNotional: Double,
    val entryOffset: Double,
    var trailArmed: Boolean = false,
    var peakPrice: Double = entryPrice,
    var troughPrice: Double = entryPrice,
    var adverse1sPct: Double? = null,
    var adverse5sPct: Double? = null
)

data class ScalperStats(
    var ordersPlaced: Long = 0,
    var ordersFilled: Long = 0,
    var ordersCanceled: Long = 0,
    var trades: Long = 0,
    var wins: Long = 0,
    var losses: Long = 0,
    var grossPnl: Double = 0.0,
    var netPnl: Double = 0.0,
    var fillTimeSumMs: Long = 0,
    var fillTimeSamples: Long = 0,
    var adverse1sSum: Double = 0.0,
    var adverse1sCount: Long = 0,
    var adverse5sSum: Double = 0.0,
    var adverse5sCount: Long = 0
)

data class GlobalState(
    var equity: Double,
    var peakEquity: Double,
    var openPositions: Int,
    var killSwitch: Boolean
)
