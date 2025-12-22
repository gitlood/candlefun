package com.example.tradebot

import com.example.network.interfaces.BinanceTestNetApiService
import com.example.platformutil.PatternBuckets
import com.example.platformutil.model.BotSpec
import com.example.platformutil.model.Candle
import com.example.platformutil.model.ExecutionMode
import com.example.platformutil.model.OrderBookSnapshot
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * TradeBot (gate semantics aligned with BacktestFeatures):
 *  - ret30m: FAIL if ret30m < ret30mMax
 *  - volumeZ: FAIL if volumeZ < volumeZMin
 *  - contractionRatio (recentVolStd / baselineVolStd): FAIL if ratio > contractionMin
 *
 * NOTE: This treats SignalConfig.contractionMin as "contractionRatioMax".
 * Example good starting values for ETHUSDT 5m:
 *   volumeZMin = 0.0 .. 0.5
 *   contractionMin = 0.95 .. 0.85
 */
class TradeBot(
    private val api: BinanceTestNetApiService,
    val spec: BotSpec
) {
    val cfg = spec.cfg
    val patterns: Set<String> = normalizePatterns(spec.patterns.toList())

    var consoleColorCode: Int = 37 // default white

    data class LongPos(val entryTime: Long, val entryPrice: Double)
    val openPositions = ArrayList<LongPos>(spec.trade.maxOpenPositions)

    // ✅ per-bot tracking (each bot processes each new candle once)
    private var lastProcessedOpenTime: Long = Long.MIN_VALUE

    private var printedInit = false

    suspend fun onCandles(candlesRaw: List<Candle>, orderBookSnapshot: OrderBookSnapshot? = null) {
        if (candlesRaw.isEmpty()) return

        val sorted = candlesRaw.sortedBy { it.openTime }
        val latest = sorted.last()

        // per-bot new candle check
        if (latest.openTime <= lastProcessedOpenTime) return
        lastProcessedOpenTime = latest.openTime

        val intervalMillis = TradeBotMath.inferIntervalMillis(sorted)

        if (!printedInit) {
            log("INIT patterns.size=${patterns.size} sample=${patterns.take(8)}")
            printedInit = true
        }

        val lookbackBars = TradeBotMath.barsFromMinutes(cfg.backtest.lookbackMinutes, intervalMillis)
        val patternBars = cfg.eventStudy.patternBars
        val contextBars = cfg.eventStudy.contextBars
        val requiredBars = requiredBarsForWindow(lookbackBars, patternBars, contextBars)

        if (sorted.size < requiredBars) {
            log("Not enough candles yet. have=${sorted.size} need=$requiredBars (lookback=$lookbackBars patternBars=$patternBars)")
            return
        }

        val recentCandles = sorted.takeLast(requiredBars)
        val lastClose = latest.close.toDoubleOrNull() ?: return
        val entryOpen = latest.open.toDoubleOrNull() ?: return

        log("OpenPositions=${openPositions.size}")

        val exited = exitPositions(latest, lastClose)
        if (!exited) {
            tryEnterPosition(
                candles = recentCandles,
                entryOpen = entryOpen,
                lookbackBars = lookbackBars.coerceAtMost(recentCandles.size),
                patternBars = patternBars.coerceAtMost(recentCandles.size),
                contextBars = contextBars.coerceAtMost(recentCandles.size),
                intervalMillis = intervalMillis,
                currentTime = latest.openTime,
                orderBookSnapshot = orderBookSnapshot
            )
        }
    }

    private suspend fun exitPositions(latest: Candle, lastClose: Double): Boolean {
        if (openPositions.isEmpty()) return false

        var exited = false
        val it = openPositions.iterator()
        while (it.hasNext()) {
            val pos = it.next()

            val tp = pos.entryPrice * (1.0 + abs(cfg.backtest.takeProfit))
            val sl = pos.entryPrice * (1.0 - abs(cfg.backtest.stopLoss))
            val hitTp = lastClose >= tp
            val hitSl = lastClose <= sl
            val hitHz = (latest.openTime - pos.entryTime) >= (cfg.backtest.horizonMinutes * 60_000L)

            if (hitTp || hitSl || hitHz) {
                val reason = when {
                    hitSl -> "SL"
                    hitTp -> "TP"
                    else -> "HZ"
                }
                log("EXIT ✅ reason=$reason lastClose=$lastClose entry=${pos.entryPrice}")

                if (spec.trade.mode == ExecutionMode.TESTNET) {
                    api.createOrder(spec.trade.symbol, "SELL", "MARKET", spec.trade.quantity, null, null)
                }
                it.remove()
                exited = true
            }
        }
        return exited
    }

    private suspend fun tryEnterPosition(
        candles: List<Candle>,
        entryOpen: Double,
        lookbackBars: Int,
        patternBars: Int,
        contextBars: Int,
        intervalMillis: Long,
        currentTime: Long,
        orderBookSnapshot: OrderBookSnapshot?
    ) {
        if (openPositions.size >= spec.trade.maxOpenPositions) {
            log("Max open positions reached (${openPositions.size}). Skipping entry.")
            return
        }

        val entryIndex = candles.lastIndex
        val signalIndex = entryIndex - 1
        if (signalIndex < 1) {
            log("Not enough candles for signal evaluation yet.")
            return
        }

        val gate = passesSignalGate(
            candles = candles,
            signalIndex = signalIndex,
            lookbackBars = lookbackBars,
            intervalMillis = intervalMillis
        )

        log(
            "Signal Gate -> ${if (gate.ok) "PASS ✅" else "FAIL ❌"} " +
                    "(ret30m=${fmt(gate.ret30m)} min=${fmt(gate.ret30mMax)} " +
                    "volumeZ=${fmt(gate.volumeZ)} min=${fmt(gate.volumeZMin)} " +
                    "contractionRatio=${fmt(gate.contractionRatio)} max=${fmt(gate.contractionRatioMax)} " +
                    "slope=${fmt(gate.trendSlope)} min=${fmt(gate.trendSlopeMin)})"
        )

        val keys = currentPatternKey(candles, entryIndex, patternBars, contextBars)
        if (keys == null) {
            log("Pattern key extraction failed. Skipping.")
            return
        }

        val matched = (keys.fullKey in patterns) || (keys.seqWithLast in patterns) || (keys.seqOnly in patterns)
        log("Pattern key -> ${keys.fullKey}")
        log("Pattern match -> ${if (matched) "MATCH ✅" else "NO MATCH ❌"}")

        val orderBookOk = passesOrderBookGate(orderBookSnapshot)
        log("OrderBook Gate -> ${if (orderBookOk) "PASS ✅" else "FAIL ❌"}")

        if (!matched || !gate.ok || !orderBookOk) return

        val fillPrice = if (spec.trade.mode == ExecutionMode.TESTNET) {
            val resp = api.createOrder(spec.trade.symbol, "BUY", "MARKET", spec.trade.quantity, null, null)
            resp.bestEffortPrice() ?: entryOpen
        } else {
            entryOpen
        }

        openPositions.add(LongPos(currentTime, fillPrice))
        log("ENTERED LONG ✅ price=$fillPrice qty=${spec.trade.quantity} openPositions=${openPositions.size}")
    }

    // ----------------------- Pattern helpers -----------------------

    private data class PatternKey(val seqOnly: String, val seqWithLast: String, val fullKey: String)

    private fun currentPatternKey(
        candles: List<Candle>,
        entryIndex: Int,
        patternBars: Int,
        contextBars: Int
    ): PatternKey? {
        val startPat = entryIndex - patternBars
        val startCtx = (entryIndex - contextBars).coerceAtLeast(0)
        if (startPat < 1 || entryIndex <= 1) return null

        val seq = StringBuilder()
        var lastShape = "N"
        var patRangeSum = 0.0
        var patVolSum = 0.0

        fun d(s: String) = s.toDoubleOrNull()

        var rangeSum = 0.0
        var rangeN = 0
        var volSum = 0.0
        var volN = 0
        for (i in startCtx until entryIndex) {
            val h = d(candles[i].high) ?: continue
            val l = d(candles[i].low) ?: continue
            val v = d(candles[i].volume) ?: 0.0
            val r = h - l
            if (r > 0.0) {
                rangeSum += r; rangeN++
            }
            volSum += v; volN++
        }
        val meanRange = if (rangeN == 0) 0.0 else rangeSum / rangeN
        val meanVol = if (volN == 0) 0.0 else volSum / volN

        for (i in startPat until entryIndex) {
            val o = d(candles[i].open) ?: return null
            val h = d(candles[i].high) ?: return null
            val l = d(candles[i].low) ?: return null
            val c = d(candles[i].close) ?: return null
            val v = d(candles[i].volume) ?: 0.0

            val range = h - l
            if (range <= 0.0) return null

            patRangeSum += range
            patVolSum += v

            val bodyFrac = abs(c - o) / range
            val dir = when {
                bodyFrac < 0.12 -> 'D'
                c > o -> 'G'
                else -> 'R'
            }
            val b = when {
                bodyFrac < 0.25 -> 0
                bodyFrac < 0.55 -> 1
                else -> 2
            }

            if (seq.isNotEmpty()) seq.append('.')
            seq.append(dir).append(b)

            if (i == entryIndex - 1) {
                lastShape = when {
                    bodyFrac < 0.12 -> "D"
                    (h - max(o, c)) / range > 0.55 && bodyFrac < 0.35 -> "PU"
                    (min(o, c) - l) / range > 0.55 && bodyFrac < 0.35 -> "PL"
                    else -> "N"
                }
            }
        }

        val seqOnly = "seq=$seq"
        val seqWithLast = "seq=$seq|last=$lastShape"
        val patRangeMean = patRangeSum / patternBars.toDouble()
        val patVolMean = patVolSum / patternBars.toDouble()

        val rangeRel = if (meanRange <= 0.0) Double.NaN else patRangeMean / meanRange
        val volRel = if (meanVol <= 0.0) Double.NaN else patVolMean / meanVol

        val c0 = d(candles[startPat].close) ?: return null
        val c1 = d(candles[entryIndex - 1].close) ?: return null
        val ret = if (c0 == 0.0) 0.0 else (c1 / c0) - 1.0

        val retB = PatternBuckets.bucketRet(ret)
        val rngB = PatternBuckets.bucketRangeRatio(rangeRel)
        val volB = PatternBuckets.bucketVolumeRatio(volRel)

        val fullKey = "seq=$seq|ret=$retB|rng=$rngB|vol=$volB|last=$lastShape"
        return PatternKey(seqOnly, seqWithLast, fullKey)
    }

    private fun normalizePatterns(raw: List<String>): Set<String> {
        return raw.flatMap { lineRaw ->
            val line = lineRaw.trim()
            if (line.isBlank()) return@flatMap emptyList()

            val token0 = Regex("""pattern=([^\s]+)""").find(line)?.groupValues?.get(1) ?: line
            val token1 = token0
                .trim()
                .trim('"')
                .trim('\'')
                .replace("seq_", "seq=")
                .replace("last_", "last=")

            val parts = token1.split('|').map { it.trim() }.filter { it.isNotBlank() }

            val seqPart = parts.firstOrNull { it.startsWith("seq=") } ?: return@flatMap emptyList()
            val retPart = parts.firstOrNull { it.startsWith("ret=") }
            val rngPart = parts.firstOrNull { it.startsWith("rng=") }
            val volPart = parts.firstOrNull { it.startsWith("vol=") }
            val lastPart = parts.firstOrNull { it.startsWith("last=") }

            val hasBuckets = retPart != null || rngPart != null || volPart != null
            if (hasBuckets) {
                listOf(
                    buildList {
                        add(seqPart)
                        if (retPart != null) add(retPart)
                        if (rngPart != null) add(rngPart)
                        if (volPart != null) add(volPart)
                        if (lastPart != null) add(lastPart)
                    }.joinToString("|")
                )
            } else if (lastPart != null) {
                listOf(seqPart, "$seqPart|$lastPart")
            } else {
                listOf(seqPart)
            }
        }.toHashSet()
    }

    // ----------------------- Signal Gate (BacktestFeatures semantics) -----------------------

    private data class Gate(
        val ok: Boolean,
        val ret30m: Double,
        val ret30mMax: Double,
        val volumeZ: Double,
        val volumeZMin: Double,
        val contractionRatio: Double,
        val contractionRatioMax: Double,
        val trendSlope: Double,
        val trendSlopeMin: Double
    )

    private fun passesSignalGate(
        candles: List<Candle>,
        signalIndex: Int,
        lookbackBars: Int,
        intervalMillis: Long
    ): Gate {
        fun d(s: String) = s.toDoubleOrNull()

        val end = signalIndex
        val lb = lookbackBars.coerceAtMost(candles.size).coerceAtLeast(2)
        val start = end - lb + 1
        if (start < 0) {
            return Gate(
                ok = false,
                ret30m = 0.0,
                ret30mMax = cfg.signal.ret30mMin,
                volumeZ = 0.0,
                volumeZMin = cfg.signal.volumeZMin,
                contractionRatio = 0.0,
                contractionRatioMax = cfg.signal.contractionMax,
                trendSlope = 0.0,
                trendSlopeMin = cfg.signal.trendSlopeMin
            )
        }

        // closes/volumes window [start..end]
        val closes = DoubleArray(lb) { i ->
            d(candles[start + i].close) ?: return Gate(
                ok = false,
                ret30m = 0.0,
                ret30mMax = cfg.signal.ret30mMin,
                volumeZ = 0.0,
                volumeZMin = cfg.signal.volumeZMin,
                contractionRatio = 0.0,
                contractionRatioMax = cfg.signal.contractionMax,
                trendSlope = 0.0,
                trendSlopeMin = cfg.signal.trendSlopeMin
            )
        }
        val vols = DoubleArray(lb) { i -> d(candles[start + i].volume) ?: 0.0 }

        // --- ret30m on actual interval ---
        val b30 = TradeBotMath.barsFromMinutes(30, intervalMillis)
        val ret30m = if (lb > b30 && b30 >= 1) {
            val a = closes[lb - 1 - b30]
            val b = closes[lb - 1]
            if (a > 0.0) (b / a) - 1.0 else 0.0
        } else {
            0.0
        }

        // --- volumeZ ---
        val vNow = vols.last()
        val vMean = mean(vols)
        val vStd = std(vols)
        val volumeZ = if (vStd == 0.0) 0.0 else (vNow - vMean) / vStd

        // --- contraction ratio = volStdRecent / volStd (same as CandleSeries.featuresAt) ---
        val rets = DoubleArray(lb - 1) { i -> (closes[i + 1] / closes[i]) - 1.0 }
        val volStd = std(rets)
        val lastN = min(10, rets.size)
        val volStdRecent =
            if (lastN >= 2) std(rets.copyOfRange(rets.size - lastN, rets.size)) else 0.0
        val contractionRatio = if (volStd == 0.0) 0.0 else volStdRecent / volStd

        val trendSlope = slopeFromCloses(closes)

        val s = cfg.signal

        // ✅ semantics you requested
        val retOk = ret30m >= s.ret30mMin              // fail if ret30m < ret30mMax
        val volOk = volumeZ >= s.volumeZMin
        val contractionOk = contractionRatio <= s.contractionMax // fail if ratio > contractionMin (max ratio)
        val trendOk = trendSlope >= s.trendSlopeMin

        return Gate(
            ok = retOk && volOk && contractionOk && trendOk,
            ret30m = ret30m,
            ret30mMax = s.ret30mMin,
            volumeZ = volumeZ,
            volumeZMin = s.volumeZMin,
            contractionRatio = contractionRatio,
            contractionRatioMax = s.contractionMax,
            trendSlope = trendSlope,
            trendSlopeMin = s.trendSlopeMin
        )
    }

    // ----------------------- Math helpers -----------------------

    private fun mean(a: DoubleArray) = if (a.isEmpty()) 0.0 else a.sum() / a.size

    private fun std(a: DoubleArray): Double {
        if (a.size <= 1) return 0.0
        val m = mean(a)
        var ss = 0.0
        for (x in a) ss += (x - m) * (x - m)
        return sqrt(max(0.0, ss / a.size))
    }

    private fun slopeFromCloses(closes: DoubleArray): Double {
        val n = closes.size
        if (n < 2) return 0.0
        val nPts = n.toDouble()
        val sumX = (n - 1) * nPts / 2.0
        val sumX2 = (n - 1) * nPts * (2 * n - 1) / 6.0
        var sumY = 0.0
        var sumXY = 0.0
        for (i in closes.indices) {
            val y = closes[i]
            sumY += y
            sumXY += i * y
        }
        val denom = nPts * sumX2 - sumX * sumX
        return if (denom == 0.0) 0.0 else (nPts * sumXY - sumX * sumY) / denom
    }

    private fun requiredBarsForWindow(lookbackBars: Int, patternBars: Int, contextBars: Int): Int {
        val minForPattern = patternBars + 2
        val minForContext = contextBars + 1
        val minForGate = lookbackBars + 1
        return max(max(minForPattern, minForContext), minForGate).coerceAtLeast(2)
    }

    private fun passesOrderBookGate(snapshot: OrderBookSnapshot?): Boolean {
        val ob = cfg.orderBook
        if (!ob.enabled) return true
        if (snapshot == null) {
            log("OrderBook Gate -> missing snapshot")
            return false
        }

        val spreadBps = if (snapshot.midPrice > 0.0) (snapshot.spread / snapshot.midPrice) * 10_000.0 else Double.POSITIVE_INFINITY
        val spreadOk = spreadBps <= ob.maxSpreadBps
        val imbalanceOk = snapshot.imbalance10 >= ob.minImbalance10
        return spreadOk && imbalanceOk
    }

    private fun com.example.network.model.TradeResponse.bestEffortPrice(): Double? {
        val p = try {
            this::class.members.firstOrNull { it.name == "price" }?.call(this) as? String
        } catch (_: Throwable) {
            null
        }
        return p?.toDoubleOrNull()
    }

    private fun log(msg: String) {
        println("[${spec.name.color(consoleColorCode)}] $msg")
    }

    private fun fmt(x: Double): String = "%.4f".format(x)

    private fun String.color(code: Int) = "\u001B[${code}m$this\u001B[0m"
}
