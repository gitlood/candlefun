package com.example.stackeddca

import com.example.liveklines.LiveCandle
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.math.*

class StackedDcaEngine(
    private val cfg: BacktestConfig,
    private val interval: String,
    private val onEvent: ((BacktestEvent) -> Unit)? = null,
) {
    private val intervalMs = com.example.platformutil.intervalToMillis(interval)
    private val barsPerDay = (TimeUnit.DAYS.toMillis(1) / intervalMs).toInt()
    private val window = ArrayDeque<LiveCandle>(barsPerDay * max(1, cfg.trendDays + 1) + 5)

    private var cash = cfg.startEquity
    private var positionQty = 0.0
    private var positionNotional = 0.0
    private var positionFees = 0.0
    private var positionVolPctRef = 0.0
    private var positionPeak = 0.0
    private var trailingArmed = false
    private var lastFillPrice: Double? = null
    private var layersCount = 0
    private var pendingOrder: PendingOrder? = null
    private var currentDayStart: Long? = null

    fun equity(lastPrice: Double): Double = cash + positionQty * lastPrice

    fun onCandle(symbol: String, c: LiveCandle) {
        // keep rolling history
        window.addLast(c)
        while (window.size > barsPerDay * (max(1, cfg.trendDays) + 2) + 10) window.removeFirst()

        // expire pending at end of UTC day
        pendingOrder?.let { o ->
            if (c.openTime >= o.expiresAt) pendingOrder = null
        }

        val dayStart = dayStartUtc(c.openTime)
        if (currentDayStart == null || dayStart > currentDayStart!!) {
            currentDayStart = dayStart
            // place daily anchor order (once per UTC day)
            val anchor = computeDailyAnchor() ?: return
            val anchorPrice = when (cfg.entryAnchor) {
                EntryAnchor.HIGH_24 -> anchor.high24
                EntryAnchor.CLOSE -> anchor.priceRef
                EntryAnchor.LOW_24 -> anchor.low24
            }

            val rawLimit = anchorPrice * (1.0 - cfg.kEntry * anchor.volPct)
            val trendOk = anchor.trendSma?.let { anchor.priceRef >= it * (1.0 + cfg.trendMinPct) } ?: true
            val volOk = anchor.volPct >= cfg.minVolPctForEntry
            val volTooHigh = cfg.maxVolPctForEntry > 0.0 && anchor.volPct > cfg.maxVolPctForEntry
            if (!volOk || volTooHigh || !trendOk) return

            val limitCandidate = if (lastFillPrice != null) min(rawLimit, lastFillPrice!! * (1.0 - cfg.minStepPct)) else rawLimit
            val (limit, _) = clampLimitPrice(limitCandidate, anchor.priceRef)

            val maxNotional = cfg.maxNotional ?: (cfg.startEquity * cfg.maxEquityPct)
            val nextNotional = nextLayerNotional(layersCount)
            if (layersCount < cfg.maxLayers && positionNotional + nextNotional <= maxNotional) {
                pendingOrder = PendingOrder(
                    limitPrice = limit,
                    expiresAt = dayStart + TimeUnit.DAYS.toMillis(1),
                    volPct = anchor.volPct,
                    layerIndex = layersCount + 1,
                    notional = nextNotional
                )
            }
        }

        // fill pending
        pendingOrder?.let { o ->
            val fills = when (cfg.fillMode) {
                FillMode.OPTIMISTIC -> c.low <= o.limitPrice
                FillMode.CLOSE -> c.close <= o.limitPrice
                FillMode.TWO_CLOSE -> false // keep it simple; add counter if you need it
            }
            if (fills) {
                val fillPrice = if (cfg.fillMode == FillMode.OPTIMISTIC) min(o.limitPrice, c.open) else min(o.limitPrice, c.close)
                val fee = o.notional * cfg.feePct
                if (cash >= o.notional + fee) {
                    val qty = o.notional / fillPrice
                    cash -= (o.notional + fee)
                    positionQty += qty
                    positionNotional += o.notional
                    positionFees += fee
                    layersCount += 1
                    lastFillPrice = fillPrice
                    if (layersCount == 1) {
                        positionVolPctRef = o.volPct
                        positionPeak = c.high
                        trailingArmed = false
                    }
                }
                pendingOrder = null
            }
        }

        // manage open position
        if (layersCount > 0) {
            val avgEntry = positionNotional / positionQty
            positionPeak = max(positionPeak, c.high)

            val armPct = max(cfg.trailArmedMinPct, cfg.trailArmedVolFactor * positionVolPctRef)
            if (!trailingArmed && c.high >= avgEntry * (1.0 + armPct)) trailingArmed = true

            val hardStop = avgEntry * (1.0 - cfg.kHard * positionVolPctRef)
            val tp = avgEntry * (1.0 + cfg.kTp * positionVolPctRef)
            val stop = if (trailingArmed) max(hardStop, positionPeak * (1.0 - cfg.kTrail * positionVolPctRef)) else hardStop

            val exitPx = when {
                c.low <= stop -> stop
                c.high >= tp -> tp
                else -> null
            }

            if (exitPx != null) {
                val adjustedExit = exitPx * (1.0 - cfg.slippagePct)
                val exitValue = positionQty * adjustedExit
                val exitFee = exitValue * cfg.feePct
                val pnl = exitValue - exitFee - (positionNotional + positionFees)

                cash += (exitValue - exitFee)

                // reset
                positionQty = 0.0
                positionNotional = 0.0
                positionFees = 0.0
                positionVolPctRef = 0.0
                positionPeak = 0.0
                trailingArmed = false
                layersCount = 0
                lastFillPrice = null
            }
        }
    }

    private fun computeDailyAnchor(): DailyAnchor? {
        if (window.size < barsPerDay + 1) return null
        val list = window.toList()
        val endIdx = list.size - 2
        val startIdx = endIdx - barsPerDay + 1
        if (startIdx <= 0) return null

        var prevClose = list[startIdx - 1].close
        var high24 = list[startIdx].high
        var low24 = list[startIdx].low
        var atrSum = 0.0

        for (i in startIdx..endIdx) {
            val c = list[i]
            high24 = max(high24, c.high)
            low24 = min(low24, c.low)
            val tr = max(c.high - c.low, max(abs(c.high - prevClose), abs(c.low - prevClose)))
            atrSum += tr
            prevClose = c.close
        }

        val priceRef = list[endIdx].close
        if (priceRef <= 0.0) return null

        val volPct = when (cfg.volMode) {
            VolatilityMode.ATR_MEAN -> (atrSum / barsPerDay) / priceRef
            VolatilityMode.RANGE -> (high24 - low24) / priceRef
        }

        val trendBars = cfg.trendDays * barsPerDay
        val trendSma = if (trendBars > 0 && endIdx + 1 >= trendBars) {
            val trendStart = endIdx - trendBars + 1
            list.subList(trendStart, endIdx + 1).map { it.close }.average()
        } else null

        return DailyAnchor(high24, low24, volPct, priceRef, trendSma)
    }

    private fun dayStartUtc(epochMs: Long): Long =
        Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate()
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun nextLayerNotional(layersCount: Int): Double =
        when (cfg.sizeMode) {
            SizeMode.EQUAL -> cfg.baseOrderNotional
            SizeMode.GEOMETRIC -> cfg.baseOrderNotional * cfg.sizeR.pow(layersCount)
        }

    private fun clampLimitPrice(limitPrice: Double, priceRef: Double): Pair<Double, String?> {
        var adjusted = limitPrice
        var note: String? = null
        if (cfg.maxEntryDistPct > 0.0) {
            val minAllowed = priceRef * (1.0 - cfg.maxEntryDistPct)
            if (adjusted < minAllowed) { adjusted = minAllowed; note = "limitClampedMax" }
        }
        if (cfg.minEntryDistPct > 0.0) {
            val maxAllowed = priceRef * (1.0 - cfg.minEntryDistPct)
            if (adjusted > maxAllowed) { adjusted = maxAllowed; note = "limitClampedMin" }
        }
        return adjusted to note
    }

    private data class DailyAnchor(
        val high24: Double,
        val low24: Double,
        val volPct: Double,
        val priceRef: Double,
        val trendSma: Double?
    )
    private data class PendingOrder(
        val limitPrice: Double,
        val expiresAt: Long,
        val volPct: Double,
        val layerIndex: Int,
        val notional: Double
    )
}
