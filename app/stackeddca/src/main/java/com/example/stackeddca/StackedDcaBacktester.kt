package com.example.stackeddca

import com.example.platformutil.intervalToMillis
import com.example.platformutil.model.Candle
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class BacktestResult(
    val symbol: String,
    val startEquity: Double,
    val trades: Int,
    val wins: Int,
    val losses: Int,
    val totalPnl: Double,
    val totalReturnPct: Double,
    val maxDrawdownPct: Double,
    val profitFactor: Double,
    val expectancy: Double,
    val maxLayersUsed: Int,
    val pctTimeAtMaxLayers: Double,
    val avgExposurePct: Double,
    val maxExposurePct: Double,
    val maxRecoveryHours: Double,
    val finalEquity: Double
)

class StackedDcaBacktester(
    private val config: BacktestConfig,
    interval: String
) {
    private val intervalMillis = intervalToMillis(interval)
    private val barsPerDay = (TimeUnit.DAYS.toMillis(1) / intervalMillis).toInt()

    fun run(
        symbol: String,
        rawCandles: List<Candle>,
        eventSink: ((BacktestEvent) -> Unit)? = null,
        debugSink: ((DailyDebugSummary) -> Unit)? = null
    ): BacktestResult {
        val candles = rawCandles.mapNotNull { it.toMinuteCandle() }.sortedBy { it.openTime }
        if (candles.isEmpty()) {
            return emptyResult(symbol, config.startEquity)
        }

        val maxNotional = config.maxNotional ?: (config.startEquity * config.maxEquityPct)

        var cash = config.startEquity
        var dailyDebug: DailyDebugSummary? = null
        var currentDayPeakEquity = cash
        var currentDayLowEquity = cash
        var currentDayDrawdownPct = 0.0
        var positionQty = 0.0
        var positionNotional = 0.0
        var positionFees = 0.0
        var positionVolPctRef = 0.0
        var positionPeak = 0.0
        var trailingArmed = false
        var lastFillPrice: Double? = null
        var layersCount = 0
        var maxLayersUsed = 0
        var pendingOrder: PendingOrder? = null
        var currentDayStart: Long? = null

        fun flushDailyDebug() {
            dailyDebug?.let {
                debugSink?.invoke(it)
                dailyDebug = null
            }
        }

        fun startDailyDebug(dayStart: Long) {
            flushDailyDebug()
            currentDayPeakEquity = cash
            currentDayLowEquity = cash
            currentDayDrawdownPct = 0.0
            dailyDebug = DailyDebugSummary(
                symbol = symbol,
                dayStart = dayStart,
                dayPeakEquity = cash,
                dayLowEquity = cash,
                trendDays = config.trendDays
            )
        }

        val metrics = Metrics(config.startEquity, candles.first().openTime)

        for (i in candles.indices) {
            val candle = candles[i]
            val dayStart = dayStartUtc(candle.openTime)
            if (pendingOrder != null && candle.openTime >= pendingOrder.expiresAt) {
                emitEvent(
                    eventSink = eventSink,
                    timestamp = candle.openTime,
                    symbol = symbol,
                    type = EventType.CANCEL,
                    layer = pendingOrder.layerIndex,
                    price = pendingOrder.limitPrice,
                    qty = pendingOrder.qty,
                    notional = pendingOrder.notional,
                    fee = 0.0,
                    slippagePct = 0.0,
                    avgEntry = currentAvgEntry(positionNotional, positionQty),
                    volPctRef = pendingOrder.volPct,
                    cash = cash,
                    equity = cash + positionQty * candle.close,
                    pnl = 0.0,
                    reason = "expired"
                )
                pendingOrder = null
                dailyDebug = dailyDebug?.copy(comment = "orderExpired")
            }

            if (currentDayStart == null || dayStart > currentDayStart) {
                currentDayStart = dayStart
                startDailyDebug(dayStart)

                val anchor = computeDailyAnchor(candles, i)
                if (anchor != null) {
                    val anchorPrice = when (config.entryAnchor) {
                        EntryAnchor.HIGH_24 -> anchor.high24
                        EntryAnchor.CLOSE -> anchor.priceRef
                        EntryAnchor.LOW_24 -> anchor.low24
                    }
                    val calculatedRawLimit = anchorPrice * (1.0 - config.kEntry * anchor.volPct)
                    val trendOk = anchor.trendSma?.let { anchor.priceRef >= it * (1.0 + config.trendMinPct) } ?: true
                    dailyDebug = dailyDebug?.copy(
                        volPct = anchor.volPct,
                        priceRef = anchor.priceRef,
                        anchorPrice = anchorPrice,
                        rawLimit = calculatedRawLimit,
                        entryAttempted = true,
                        trendSma = anchor.trendSma ?: 0.0,
                        trendOk = trendOk
                    )
                    val volOk = anchor.volPct >= config.minVolPctForEntry
                    val volTooHigh = config.maxVolPctForEntry > 0.0 && anchor.volPct > config.maxVolPctForEntry
                    if (!volOk) {
                        dailyDebug = dailyDebug?.copy(comment = "volBelowMin")
                    } else if (volTooHigh) {
                        dailyDebug = dailyDebug?.copy(comment = "volAboveMax")
                    } else if (!trendOk) {
                        dailyDebug = dailyDebug?.copy(comment = "trendBelowSma")
                    } else {
                        val limitPriceCandidate = if (lastFillPrice != null) {
                            min(calculatedRawLimit, lastFillPrice * (1.0 - config.minStepPct))
                        } else {
                            calculatedRawLimit
                        }
                        val (clampedLimit, clampNote) = clampLimitPrice(
                            limitPriceCandidate,
                            anchor.priceRef,
                            config.minEntryDistPct,
                            config.maxEntryDistPct,
                            lastFillPrice
                        )
                        dailyDebug = dailyDebug?.copy(
                            limitPrice = clampedLimit,
                            comment = clampNote ?: dailyDebug?.comment.orEmpty()
                        )

                        val nextNotional = nextLayerNotional(layersCount)
                        if (clampedLimit > 0.0 && canPlaceLayer(anchor.priceRef, layersCount, positionNotional, positionQty, maxNotional, nextNotional)) {
                            pendingOrder = PendingOrder(
                                limitPrice = clampedLimit,
                                expiresAt = dayStart + TimeUnit.DAYS.toMillis(1),
                                volPct = anchor.volPct,
                                layerIndex = layersCount + 1,
                                notional = nextNotional
                            )

                            emitEvent(
                                eventSink = eventSink,
                                timestamp = candle.openTime,
                                symbol = symbol,
                                type = EventType.ORDER,
                                layer = pendingOrder.layerIndex,
                                price = pendingOrder.limitPrice,
                                qty = pendingOrder.qty,
                                notional = pendingOrder.notional,
                                fee = 0.0,
                                slippagePct = 0.0,
                                avgEntry = currentAvgEntry(positionNotional, positionQty),
                                volPctRef = pendingOrder.volPct,
                                cash = cash,
                                equity = cash + positionQty * candle.close,
                                pnl = 0.0,
                                reason = "daily_limit"
                            )
                        }
                        else if (clampedLimit > 0.0) {
                            dailyDebug = dailyDebug?.copy(comment = "layerBlocked")
                        }
                    }
                }
            }

            pendingOrder = pendingOrder?.let { order ->
                if (!shouldFill(order, candle)) return@let order

                val nextNotional = order.notional
                val fee = nextNotional * config.feePct
                if (!canFillLayer(order.limitPrice, layersCount, positionNotional, positionQty, maxNotional, cash, nextNotional, fee)) {
                    return@let null
                }

                val fillPrice = resolveFillPrice(order, candle)
                val qty = nextNotional / fillPrice
                cash -= (nextNotional + fee)
                positionQty += qty
                positionNotional += nextNotional
                positionFees += fee
                layersCount += 1
                maxLayersUsed = max(maxLayersUsed, layersCount)
                lastFillPrice = fillPrice

                val prevMaxLayers = dailyDebug?.maxLayers ?: 0
                val updatedMaxLayers = max(prevMaxLayers, layersCount)
                dailyDebug = dailyDebug?.copy(
                    entryFilled = true,
                    fillPrice = fillPrice,
                    maxLayers = updatedMaxLayers
                )

                if (layersCount == 1) {
                    positionVolPctRef = order.volPct
                    positionPeak = candle.high
                    trailingArmed = false
                }

                emitEvent(
                    eventSink = eventSink,
                    timestamp = candle.openTime,
                    symbol = symbol,
                    type = EventType.FILL,
                    layer = layersCount,
                    price = fillPrice,
                    qty = qty,
                    notional = nextNotional,
                    fee = fee,
                    slippagePct = 0.0,
                    avgEntry = currentAvgEntry(positionNotional, positionQty),
                    volPctRef = positionVolPctRef,
                    cash = cash,
                    equity = cash + positionQty * candle.close,
                    pnl = 0.0,
                    reason = "limit_fill"
                )

                null
            }

            if (layersCount > 0) {
                val avgEntry = positionNotional / positionQty
                positionPeak = max(positionPeak, candle.high)

                val trailArmedPct = max(config.trailArmedMinPct, config.trailArmedVolFactor * positionVolPctRef)
                if (!trailingArmed && candle.high >= avgEntry * (1.0 + trailArmedPct)) {
                    trailingArmed = true
                }

                val hardStop = avgEntry * (1.0 - config.kHard * positionVolPctRef)
                val tp = avgEntry * (1.0 + config.kTp * positionVolPctRef)
                val portfolioStop = if (trailingArmed) {
                    val trailStop = positionPeak * (1.0 - config.kTrail * positionVolPctRef)
                    max(hardStop, trailStop)
                } else {
                    hardStop
                }

                val exitPrice = when {
                    candle.low <= portfolioStop -> portfolioStop
                    candle.high >= tp -> tp
                    else -> null
                }

                if (exitPrice != null) {
                    val adjustedExit = exitPrice * (1.0 - config.slippagePct)
                    val exitValue = positionQty * adjustedExit
                    val exitFee = exitValue * config.feePct
                    val totalEntryCost = positionNotional + positionFees
                    val pnl = exitValue - exitFee - totalEntryCost
                    val exitReason = if (candle.low <= portfolioStop) "stop" else "tp"

                    val cashAfter = cash + (exitValue - exitFee)

                    emitEvent(
                        eventSink = eventSink,
                        timestamp = candle.openTime,
                        symbol = symbol,
                        type = EventType.EXIT,
                        layer = layersCount,
                        price = adjustedExit,
                        qty = positionQty,
                        notional = exitValue,
                        fee = exitFee,
                        slippagePct = config.slippagePct,
                        avgEntry = avgEntry,
                        volPctRef = positionVolPctRef,
                        cash = cashAfter,
                        equity = cashAfter,
                        pnl = pnl,
                        reason = exitReason
                    )

                    val prevMaxLayersAfterExit = dailyDebug?.maxLayers ?: 0
                    val updatedMaxLayersAfterExit = max(prevMaxLayersAfterExit, layersCount)
                    dailyDebug = dailyDebug?.copy(
                        exitReason = exitReason,
                        exitPrice = adjustedExit,
                        maxLayers = updatedMaxLayersAfterExit
                    )

                    cash = cashAfter
                    metrics.recordTrade(pnl)

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

            val openNotional = positionNotional
            val equity = cash + positionQty * candle.close
            currentDayPeakEquity = max(currentDayPeakEquity, equity)
            currentDayLowEquity = min(currentDayLowEquity, equity)
            if (currentDayPeakEquity > 0.0) {
                currentDayDrawdownPct = min(currentDayDrawdownPct, (equity - currentDayPeakEquity) / currentDayPeakEquity)
            }
            val prevDailyMaxLayers = dailyDebug?.maxLayers ?: 0
            val updatedDailyMaxLayers = max(prevDailyMaxLayers, layersCount)
            dailyDebug = dailyDebug?.copy(
                dayPeakEquity = currentDayPeakEquity,
                dayLowEquity = currentDayLowEquity,
                dayDrawdownPct = -currentDayDrawdownPct,
                maxLayers = updatedDailyMaxLayers
            )
            metrics.update(equity, candle.openTime, openNotional, layersCount, config.maxLayers)
        }

        if (layersCount > 0 && config.forceExitAtEnd) {
            val last = candles.last()
            val adjustedExit = last.close * (1.0 - config.slippagePct)
            val exitValue = positionQty * adjustedExit
            val exitFee = exitValue * config.feePct
            val totalEntryCost = positionNotional + positionFees
            val pnl = exitValue - exitFee - totalEntryCost

            val cashAfter = cash + (exitValue - exitFee)

            emitEvent(
                eventSink = eventSink,
                timestamp = last.openTime,
                symbol = symbol,
                type = EventType.EXIT,
                layer = layersCount,
                price = adjustedExit,
                qty = positionQty,
                notional = exitValue,
                fee = exitFee,
                slippagePct = config.slippagePct,
                avgEntry = positionNotional / positionQty,
                volPctRef = positionVolPctRef,
                cash = cashAfter,
                equity = cashAfter,
                pnl = pnl,
                reason = "force_end"
            )

            dailyDebug = dailyDebug?.copy(
                exitReason = "force_end",
                exitPrice = adjustedExit
            )

            cash = cashAfter
            metrics.recordTrade(pnl)

            val equity = cash
            currentDayPeakEquity = max(currentDayPeakEquity, equity)
            currentDayLowEquity = min(currentDayLowEquity, equity)
            if (currentDayPeakEquity > 0.0) {
                currentDayDrawdownPct = min(currentDayDrawdownPct, (equity - currentDayPeakEquity) / currentDayPeakEquity)
            }
            val prevDailyMaxLayersEnd = dailyDebug?.maxLayers ?: 0
            val updatedDailyMaxLayersEnd = max(prevDailyMaxLayersEnd, layersCount)
            dailyDebug = dailyDebug?.copy(
                dayPeakEquity = currentDayPeakEquity,
                dayLowEquity = currentDayLowEquity,
                dayDrawdownPct = -currentDayDrawdownPct,
                maxLayers = updatedDailyMaxLayersEnd
            )
            metrics.update(equity, last.openTime, 0.0, 0, config.maxLayers)
        }

        flushDailyDebug()

        return metrics.toResult(symbol, cash, maxLayersUsed)
    }

    private fun computeDailyAnchor(candles: List<MinuteCandle>, index: Int): DailyAnchor? {
        if (index < barsPerDay) return null
        val startIdx = index - barsPerDay
        val endIdx = index - 1
        var prevClose = if (startIdx > 0) candles[startIdx - 1].close else candles[startIdx].close
        var high24 = candles[startIdx].high
        var low24 = candles[startIdx].low
        var atrSum = 0.0

        for (i in startIdx..endIdx) {
            val c = candles[i]
            high24 = max(high24, c.high)
            low24 = min(low24, c.low)
            val tr = max(c.high - c.low, max(abs(c.high - prevClose), abs(c.low - prevClose)))
            atrSum += tr
            prevClose = c.close
        }

        val priceRef = candles[endIdx].close
        if (priceRef <= 0.0) return null
        val volPct = when (config.volMode) {
            VolatilityMode.ATR_MEAN -> {
                val atr = atrSum / barsPerDay
                if (atr <= 0.0) return null
                atr / priceRef
            }
            VolatilityMode.RANGE -> {
                val range = high24 - low24
                if (range <= 0.0) return null
                range / priceRef
            }
        }

        val trendBars = config.trendDays * barsPerDay
        val trendSma = if (trendBars > 0 && endIdx + 1 >= trendBars) {
            val trendStart = endIdx - trendBars + 1
            var sum = 0.0
            for (i in trendStart..endIdx) {
                sum += candles[i].close
            }
            sum / trendBars
        } else {
            null
        }
        return DailyAnchor(high24 = high24, low24 = low24, volPct = volPct, priceRef = priceRef, trendSma = trendSma)
    }

    private fun shouldFill(order: PendingOrder, candle: MinuteCandle): Boolean {
        return when (config.fillMode) {
            FillMode.OPTIMISTIC -> candle.low <= order.limitPrice
            FillMode.CLOSE -> candle.close <= order.limitPrice
            FillMode.TWO_CLOSE -> {
                order.closeBelowCount = if (candle.close <= order.limitPrice) {
                    order.closeBelowCount + 1
                } else {
                    0
                }
                order.closeBelowCount >= 2
            }
        }
    }

    private fun canPlaceLayer(
        priceRef: Double,
        layersCount: Int,
        positionNotional: Double,
        positionQty: Double,
        maxNotional: Double,
        nextNotional: Double
    ): Boolean {
        if (layersCount >= config.maxLayers) return false
        if (positionNotional + nextNotional > maxNotional) return false
        if (layersCount > 0 && config.maxDrawdownForNewLayerPct != null && positionQty > 0.0) {
            val avgEntry = positionNotional / positionQty
            val drawdown = (avgEntry - priceRef) / avgEntry
            if (drawdown > config.maxDrawdownForNewLayerPct) return false
        }
        return true
    }

    private fun canFillLayer(
        priceRef: Double,
        layersCount: Int,
        positionNotional: Double,
        positionQty: Double,
        maxNotional: Double,
        cash: Double,
        nextNotional: Double,
        fee: Double
    ): Boolean {
        if (!canPlaceLayer(priceRef, layersCount, positionNotional, positionQty, maxNotional, nextNotional)) return false
        return cash >= (nextNotional + fee)
    }

    private fun nextLayerNotional(layersCount: Int): Double {
        return when (config.sizeMode) {
            SizeMode.EQUAL -> config.baseOrderNotional
            SizeMode.GEOMETRIC -> config.baseOrderNotional * config.sizeR.pow(layersCount)
        }
    }

    private fun dayStartUtc(epochMs: Long): Long {
        val instant = Instant.ofEpochMilli(epochMs)
        return instant.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }

    private fun Candle.toMinuteCandle(): MinuteCandle? {
        val open = open.toDoubleOrNull() ?: return null
        val high = high.toDoubleOrNull() ?: return null
        val low = low.toDoubleOrNull() ?: return null
        val close = close.toDoubleOrNull() ?: return null
        if (open <= 0.0 || high <= 0.0 || low <= 0.0 || close <= 0.0) return null
        return MinuteCandle(
            openTime = openTime,
            open = open,
            high = high,
            low = low,
            close = close
        )
    }

    private fun currentAvgEntry(positionNotional: Double, positionQty: Double): Double {
        return if (positionQty > 0.0) positionNotional / positionQty else 0.0
    }

    private fun clampLimitPrice(
        limitPrice: Double,
        priceRef: Double,
        minEntryDistPct: Double,
        maxEntryDistPct: Double,
        lastFillPrice: Double?
    ): Pair<Double, String?> {
        if (limitPrice <= 0.0 || priceRef <= 0.0) return limitPrice to null
        var adjusted = limitPrice
        val stepLimit = lastFillPrice?.let { it * (1.0 - config.minStepPct) }
        var note: String? = null

        if (maxEntryDistPct > 0.0) {
            val minAllowed = priceRef * (1.0 - maxEntryDistPct)
            if (adjusted < minAllowed) {
                adjusted = minAllowed
                note = "limitClampedMax"
            }
        }
        if (minEntryDistPct > 0.0) {
            val maxAllowed = priceRef * (1.0 - minEntryDistPct)
            if (adjusted > maxAllowed) {
                adjusted = maxAllowed
                note = "limitClampedMin"
            }
        }
        if (stepLimit != null && adjusted > stepLimit) {
            adjusted = stepLimit
            note = "limitClampedStep"
        }
        return adjusted to note
    }

    private fun emitEvent(
        eventSink: ((BacktestEvent) -> Unit)?,
        timestamp: Long,
        symbol: String,
        type: EventType,
        layer: Int,
        price: Double,
        qty: Double,
        notional: Double,
        fee: Double,
        slippagePct: Double,
        avgEntry: Double,
        volPctRef: Double,
        cash: Double,
        equity: Double,
        pnl: Double,
        reason: String
    ) {
        eventSink?.invoke(
            BacktestEvent(
                timestamp = timestamp,
                symbol = symbol,
                eventType = type,
                layer = layer,
                price = price,
                qty = qty,
                notional = notional,
                fee = fee,
                slippagePct = slippagePct,
                avgEntry = avgEntry,
                volPctRef = volPctRef,
                cash = cash,
                equity = equity,
                pnl = pnl,
                reason = reason
            )
        )
    }

    private fun emptyResult(symbol: String, equity: Double): BacktestResult {
        return BacktestResult(
            symbol = symbol,
            startEquity = equity,
            trades = 0,
            wins = 0,
            losses = 0,
            totalPnl = 0.0,
            totalReturnPct = 0.0,
            maxDrawdownPct = 0.0,
            profitFactor = 0.0,
            expectancy = 0.0,
            maxLayersUsed = 0,
            pctTimeAtMaxLayers = 0.0,
            avgExposurePct = 0.0,
            maxExposurePct = 0.0,
            maxRecoveryHours = 0.0,
            finalEquity = equity
        )
    }

    private fun resolveFillPrice(order: PendingOrder, candle: MinuteCandle): Double {
        return when (config.fillMode) {
            FillMode.OPTIMISTIC -> {
                if (order.limitPrice >= candle.open) candle.open else order.limitPrice
            }
            FillMode.CLOSE,
            FillMode.TWO_CLOSE -> min(order.limitPrice, candle.close)
        }
    }

    private data class MinuteCandle(
        val openTime: Long,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double
    )

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
        val notional: Double,
        var closeBelowCount: Int = 0
    ) {
        val qty: Double = if (limitPrice > 0.0) notional / limitPrice else 0.0
    }

    private class Metrics(private val startEquity: Double, startTime: Long) {
        private var totalTrades = 0
        private var wins = 0
        private var losses = 0
        private var grossProfit = 0.0
        private var grossLoss = 0.0
        private var totalPnl = 0.0
        private var maxDrawdown = 0.0
        private var peakEquity = startEquity
        private var peakTime = startTime
        private var drawdownStart: Long? = null
        private var maxRecoveryMs = 0L
        private var openNotionalSum = 0.0
        private var maxOpenNotional = 0.0
        private var minutesAtMaxLayers = 0L
        private var totalMinutes = 0L

        fun recordTrade(pnl: Double) {
            totalTrades += 1
            totalPnl += pnl
            if (pnl >= 0.0) {
                wins += 1
                grossProfit += pnl
            } else {
                losses += 1
                grossLoss += abs(pnl)
            }
        }

        fun update(
            equity: Double,
            timestamp: Long,
            openNotional: Double,
            layersCount: Int,
            maxLayers: Int
        ) {
            totalMinutes += 1
            openNotionalSum += openNotional
            maxOpenNotional = max(maxOpenNotional, openNotional)
            if (layersCount >= maxLayers && layersCount > 0) {
                minutesAtMaxLayers += 1
            }

            if (equity > peakEquity) {
                if (drawdownStart != null) {
                    val recoveryMs = timestamp - drawdownStart!!
                    maxRecoveryMs = max(maxRecoveryMs, recoveryMs)
                    drawdownStart = null
                }
                peakEquity = equity
                peakTime = timestamp
            } else {
                val dd = (equity - peakEquity) / peakEquity
                if (dd < maxDrawdown) {
                    maxDrawdown = dd
                }
                if (drawdownStart == null && dd < 0.0) {
                    drawdownStart = peakTime
                }
            }
        }

        fun toResult(symbol: String, finalEquity: Double, maxLayersUsed: Int): BacktestResult {
            val avgExposureNotional = if (totalMinutes > 0) openNotionalSum / totalMinutes else 0.0
            val pctTimeAtMaxLayers = if (totalMinutes > 0) minutesAtMaxLayers.toDouble() / totalMinutes else 0.0
            val profitFactor = if (grossLoss > 0.0) grossProfit / grossLoss else if (grossProfit > 0.0) Double.POSITIVE_INFINITY else 0.0
            val expectancy = if (totalTrades > 0) totalPnl / totalTrades else 0.0
            val avgExposurePct = if (startEquity > 0) avgExposureNotional / startEquity * 100.0 else 0.0
            val maxExposurePct = if (startEquity > 0) maxOpenNotional / startEquity * 100.0 else 0.0
            val maxRecoveryHours = maxRecoveryMs / 3_600_000.0
            val returnPct = if (startEquity > 0) (finalEquity - startEquity) / startEquity * 100.0 else 0.0

            return BacktestResult(
                symbol = symbol,
                startEquity = startEquity,
                trades = totalTrades,
                wins = wins,
                losses = losses,
                totalPnl = totalPnl,
                totalReturnPct = returnPct,
                maxDrawdownPct = abs(maxDrawdown) * 100.0,
                profitFactor = profitFactor,
                expectancy = expectancy,
                maxLayersUsed = maxLayersUsed,
                pctTimeAtMaxLayers = pctTimeAtMaxLayers * 100.0,
                avgExposurePct = avgExposurePct,
                maxExposurePct = maxExposurePct,
                maxRecoveryHours = maxRecoveryHours,
                finalEquity = finalEquity
            )
        }
    }
}
