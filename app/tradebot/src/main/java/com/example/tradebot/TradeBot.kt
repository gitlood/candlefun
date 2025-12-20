package com.example.tradebot

import com.example.network.interfaces.BinanceTestNetApiService
import com.example.platformutil.model.BotSpec
import com.example.platformutil.model.Candle
import com.example.platformutil.model.ExecutionMode
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

    suspend fun onCandles(candlesRaw: List<Candle>) {
        if (candlesRaw.isEmpty()) return

        val sorted = candlesRaw.sortedBy { it.openTime }
        val latest = sorted.last()

        // per-bot new candle check
        if (latest.openTime <= lastProcessedOpenTime) return
        lastProcessedOpenTime = latest.openTime

        val intervalMillis = inferIntervalMillis(sorted)

        if (!printedInit) {
            log("INIT patterns.size=${patterns.size} sample=${patterns.take(8)}")
            printedInit = true
        }

        val lookbackBars = barsFromMinutes(cfg.backtest.lookbackMinutes, intervalMillis)
        val patternBars = cfg.eventStudy.patternBars
        val requiredBars = max(lookbackBars, patternBars).coerceAtLeast(2)

        if (sorted.size < requiredBars) {
            log("Not enough candles yet. have=${sorted.size} need=$requiredBars (lookback=$lookbackBars patternBars=$patternBars)")
            return
        }

        val recentCandles = sorted.takeLast(requiredBars)
        val lastClose = latest.close.toDoubleOrNull() ?: return

        log("OpenPositions=${openPositions.size}")

        exitPositions(latest, lastClose)
        tryEnterPosition(
            candles = recentCandles,
            lastClose = lastClose,
            lookbackBars = lookbackBars.coerceAtMost(recentCandles.size),
            patternBars = patternBars.coerceAtMost(recentCandles.size),
            intervalMillis = intervalMillis,
            currentTime = latest.openTime
        )
    }

    private suspend fun exitPositions(latest: Candle, lastClose: Double) {
        if (openPositions.isEmpty()) return

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
            }
        }
    }

    private suspend fun tryEnterPosition(
        candles: List<Candle>,
        lastClose: Double,
        lookbackBars: Int,
        patternBars: Int,
        intervalMillis: Long,
        currentTime: Long
    ) {
        if (openPositions.size >= spec.trade.maxOpenPositions) {
            log("Max open positions reached (${openPositions.size}). Skipping entry.")
            return
        }

        val signalIndex = candles.lastIndex

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
                    "contractionRatio=${fmt(gate.contractionRatio)} max=${fmt(gate.contractionRatioMax)})"
        )

        val keys = currentSeqKeys(candles, signalIndex, patternBars)
        if (keys == null) {
            log("Pattern key extraction failed. Skipping.")
            return
        }

        val matched = (keys.seqOnly in patterns) || (keys.seqWithLast in patterns)
        log("Pattern key -> ${keys.seqWithLast}")
        log("Pattern match -> ${if (matched) "MATCH ✅" else "NO MATCH ❌"}")

        if (!matched || !gate.ok) return

        val fillPrice = if (spec.trade.mode == ExecutionMode.TESTNET) {
            val resp = api.createOrder(spec.trade.symbol, "BUY", "MARKET", spec.trade.quantity, null, null)
            resp.bestEffortPrice() ?: lastClose
        } else {
            lastClose
        }

        openPositions.add(LongPos(currentTime, fillPrice))
        log("ENTERED LONG ✅ price=$fillPrice qty=${spec.trade.quantity} openPositions=${openPositions.size}")
    }

    // ----------------------- Pattern helpers -----------------------

    private data class KeyPair(val seqOnly: String, val seqWithLast: String)

    private fun currentSeqKeys(candles: List<Candle>, signalIndex: Int, patternBars: Int): KeyPair? {
        val startPat = signalIndex - patternBars + 1
        if (startPat < 0) return null

        val seq = StringBuilder()
        var lastShape = "N"

        fun d(s: String) = s.toDoubleOrNull()

        for (i in startPat..signalIndex) {
            val o = d(candles[i].open) ?: return null
            val h = d(candles[i].high) ?: return null
            val l = d(candles[i].low) ?: return null
            val c = d(candles[i].close) ?: return null

            val range = h - l
            if (range <= 0.0) return null

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

            if (i == signalIndex) {
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
        return KeyPair(seqOnly, seqWithLast)
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
            val lastPart = parts.firstOrNull { it.startsWith("last=") }

            if (lastPart != null) listOf(seqPart, "$seqPart|$lastPart") else listOf(seqPart)
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
        val contractionRatioMax: Double
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
                contractionRatioMax = cfg.signal.contractionMax
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
                contractionRatioMax = cfg.signal.contractionMax
            )
        }
        val vols = DoubleArray(lb) { i -> d(candles[start + i].volume) ?: 0.0 }

        // --- ret30m on actual interval ---
        val b30 = barsFromMinutes(30, intervalMillis)
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

        val s = cfg.signal

        // ✅ semantics you requested
        val retOk = ret30m >= s.ret30mMin              // fail if ret30m < ret30mMax
        val volOk = volumeZ >= s.volumeZMin
        val contractionOk = contractionRatio <= s.contractionMax // fail if ratio > contractionMin (max ratio)

        return Gate(
            ok = retOk && volOk && contractionOk,
            ret30m = ret30m,
            ret30mMax = s.ret30mMin,
            volumeZ = volumeZ,
            volumeZMin = s.volumeZMin,
            contractionRatio = contractionRatio,
            contractionRatioMax = s.contractionMax
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

    private fun barsFromMinutes(minutes: Int, intervalMillis: Long): Int {
        val m = minutes.coerceAtLeast(1)
        val ms = intervalMillis.coerceAtLeast(1L)
        return ((m * 60_000L) / ms).toInt().coerceAtLeast(1)
    }

    private fun inferIntervalMillis(sortedCandles: List<Candle>): Long {
        if (sortedCandles.size < 2) return 300_000L // 5m default
        val diffs = sortedCandles.zipWithNext { a, b -> b.openTime - a.openTime }.filter { it > 0 }
        if (diffs.isEmpty()) return 300_000L
        return diffs.sorted()[diffs.size / 2] // median
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
