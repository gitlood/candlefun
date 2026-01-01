package com.example.ofi.kukanov

import com.example.platform.model.BookLevel
import com.example.platform.model.MarketState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

data class KukanovOfiConfig(
    val window: Duration = 1.seconds,
    val depthLevels: Int = 1
) {
    init {
        require(window > Duration.ZERO) { "window must be > 0" }
        require(depthLevels > 0) { "depthLevels must be > 0" }
    }
}

data class OfiSignal(
    val symbol: String,
    val timestampMs: Long,
    val eventTimeMs: Long?,
    val midPrice: Double?,
    val spread: Double?,
    val ofi: Double,
    val normalizedOfi: Double?,
    val depthNotional: Double,
    val depthQty: Double,
    val depthLevels: Int
)

class KukanovOfiAccumulator(
    private val config: KukanovOfiConfig = KukanovOfiConfig()
) {
    private var prevBids: List<BookLevel> = emptyList()
    private var prevAsks: List<BookLevel> = emptyList()
    private val window = RollingSumWindow(config.window)

    fun update(state: MarketState): OfiSignal {
        val bids = state.bidLevels.take(config.depthLevels)
        val asks = state.askLevels.take(config.depthLevels)
        val ts = state.eventTimeMs ?: state.timestampMs
        val ofiDelta = if (prevBids.isEmpty() && prevAsks.isEmpty()) {
            0.0
        } else {
            computeOfiDelta(prevBids, prevAsks, bids, asks)
        }
        window.add(ts, ofiDelta)
        val depthNotional = notional(bids) + notional(asks)
        val depthQty = quantity(bids) + quantity(asks)
        val ofi = window.current(ts)
        val normalized = if (depthNotional > 0.0) ofi / depthNotional else null

        prevBids = bids
        prevAsks = asks

        return OfiSignal(
            symbol = state.symbol,
            timestampMs = state.timestampMs,
            eventTimeMs = state.eventTimeMs,
            midPrice = state.midPrice,
            spread = state.spread,
            ofi = ofi,
            normalizedOfi = normalized,
            depthNotional = depthNotional,
            depthQty = depthQty,
            depthLevels = config.depthLevels
        )
    }

    private fun computeOfiDelta(
        prevBids: List<BookLevel>,
        prevAsks: List<BookLevel>,
        bids: List<BookLevel>,
        asks: List<BookLevel>
    ): Double {
        val bidDelta = notionalDelta(prevBids, bids)
        val askDelta = notionalDelta(prevAsks, asks)
        return bidDelta - askDelta
    }

    private fun notionalDelta(prev: List<BookLevel>, now: List<BookLevel>): Double {
        if (prev.isEmpty() && now.isEmpty()) return 0.0
        val prevMap = prev.associateBy { it.price }
        val nowMap = now.associateBy { it.price }
        val prices = HashSet<Double>(prevMap.size + nowMap.size)
        prices.addAll(prevMap.keys)
        prices.addAll(nowMap.keys)
        var sum = 0.0
        for (price in prices) {
            val prevQty = prevMap[price]?.quantity ?: 0.0
            val nowQty = nowMap[price]?.quantity ?: 0.0
            val deltaQty = nowQty - prevQty
            if (deltaQty != 0.0) {
                sum += deltaQty * price
            }
        }
        return sum
    }

    private fun notional(levels: List<BookLevel>): Double {
        var sum = 0.0
        for (lvl in levels) {
            sum += lvl.price * lvl.quantity
        }
        return sum
    }

    private fun quantity(levels: List<BookLevel>): Double {
        var sum = 0.0
        for (lvl in levels) {
            sum += lvl.quantity
        }
        return sum
    }
}

class KukanovOfiSignalBuilder(
    private val config: KukanovOfiConfig = KukanovOfiConfig()
) {
    fun stream(states: Flow<MarketState>): Flow<OfiSignal> = flow {
        val accumulator = KukanovOfiAccumulator(config)
        states.collect { state ->
            emit(accumulator.update(state))
        }
    }
}

internal class RollingSumWindow(window: Duration) {
    private val windowMs = window.inWholeMilliseconds
    private val values = ArrayDeque<TimedDouble>(128)
    private var sum = 0.0

    fun add(timestampMs: Long, value: Double) {
        values.addLast(TimedDouble(timestampMs, value))
        sum += value
        trim(timestampMs)
    }

    fun current(timestampMs: Long): Double {
        trim(timestampMs)
        return sum
    }

    private fun trim(nowMs: Long) {
        while (values.isNotEmpty() && values.first().timestampMs < nowMs - windowMs) {
            val v = values.removeFirst()
            sum -= v.value
        }
    }

    private data class TimedDouble(val timestampMs: Long, val value: Double)
}
